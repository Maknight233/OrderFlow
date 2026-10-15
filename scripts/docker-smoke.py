"""Exercise the real seven-container stack in a fresh, disposable Compose project.

Python 3.10+ standard library only. Never targets development containers/volumes.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
SERVICES = ("postgres", "redis", "kafka", "discovery-service", "order-service",
            "inventory-service", "gateway-service")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def eventually(description, check, timeout=120):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (AssertionError, OSError, RuntimeError, ValueError) as error:
            last = error
        time.sleep(1)
    raise AssertionError(f"Timed out: {description}; last error: {last}")


def free_ports(count):
    # Reserve all candidates together so this run cannot choose a port twice.
    sockets = [socket.socket() for _ in range(count)]
    try:
        for connection in sockets:
            connection.bind(("127.0.0.1", 0))
        return [connection.getsockname()[1] for connection in sockets]
    finally:
        for connection in sockets:
            connection.close()


class Smoke:
    def __init__(self, args):
        self.args = args
        self.started = datetime.now(timezone.utc)
        self.project = "orderflow-smoke-" + uuid.uuid4().hex[:10]
        self.env = os.environ.copy()
        names = ("POSTGRES_PORT", "REDIS_PORT", "KAFKA_PORT", "DISCOVERY_PORT",
                 "ORDER_PORT", "INVENTORY_PORT", "GATEWAY_PORT")
        self.ports = dict(zip(names, free_ports(len(names))))
        self.env.update({name: str(port) for name, port in self.ports.items()})
        self.env.update(POSTGRES_USER="smoke", POSTGRES_PASSWORD=uuid.uuid4().hex,
                        IMAGE_PREFIX=args.image_prefix, IMAGE_TAG=args.image_tag,
                        KAFKA_EXTERNAL_HOST="localhost", TRACING_ENABLED="false",
                        GATEWAY_CONNECT_TIMEOUT_MS="1000", GATEWAY_RESPONSE_TIMEOUT="3s",
                        GATEWAY_TOTAL_TIMEOUT="7s", GATEWAY_GET_MAX_ATTEMPTS="2",
                        GATEWAY_CB_MIN_CALLS="4", GATEWAY_CB_OPEN_WAIT="3s")
        self.base = f"http://127.0.0.1:{self.ports['GATEWAY_PORT']}"
        self.report_dir = ROOT / "integration-tests/target/docker-smoke" / self.project
        self.report_dir.mkdir(parents=True)
        self.checks = []
        self.paused = set()
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def compose(self, *args, data=None, timeout=180):
        command = ["docker", "compose", "--env-file", str(ROOT / ".env.example"),
                   "-f", str(ROOT / "docker-compose.yml"), "-p", self.project, *args]
        with (self.report_dir / "compose.log").open("a+", encoding="utf-8", errors="replace") as log:
            log.write("\n$ docker compose " + " ".join(args) + "\n")
            log.flush()
            start = log.tell()
            result = subprocess.run(command, cwd=ROOT, env=self.env, input=data,
                                    text=True, encoding="utf-8", errors="replace",
                                    stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
            log.seek(start)
            output = log.read()
        if result.returncode:
            raise RuntimeError(f"Compose {' '.join(args)} failed ({result.returncode}): {output[-3000:]}")
        return output.strip()

    def request(self, path, method="GET", body=None, expected=200, port=None):
        started = time.monotonic()
        base = self.base if port is None else f"http://127.0.0.1:{port}"
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(base + path, data=data, method=method,
                                         headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            response = self.http.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            text = response.read().decode()
            with (self.report_dir / "http.jsonl").open("a", encoding="utf-8") as log:
                log.write(json.dumps({"method": method, "path": path, "status": response.code,
                                      "elapsedSeconds": round(time.monotonic() - started, 3)}) + "\n")
            require(response.code == expected, f"{method} {path}: expected {expected}, got {response.code}: {text[:400]}")
            return json.loads(text) if text else None

    def sql(self, database, query):
        return self.compose("exec", "-T", "postgres", "psql", "-U", "smoke", "-d", database,
                            "-v", "ON_ERROR_STOP=1", "-At", "-c", query)

    def redis(self, *args):
        return self.compose("exec", "-T", "redis", "redis-cli", "--raw", *args)

    def kafka(self, tool, *args, data=None):
        return self.compose("exec", "-T", "kafka", f"/opt/kafka/bin/{tool}.sh",
                            "--bootstrap-server", "kafka:29092", *args, data=data)

    def passed(self, name, **evidence):
        self.checks.append({"name": name, "status": "PASS", "at": datetime.now(timezone.utc).isoformat(), **evidence})
        print(f"PASS {name}", flush=True)

    def up(self, build=False):
        self.compose("up", "-d", "--build" if build else "--no-build", "--wait",
                     "--wait-timeout", "300", timeout=1800 if build else 420)

    def stock(self, sku, quantity=20):
        return self.request("/api/inventory", "POST",
                            {"sku": sku, "productName": "Docker smoke", "quantity": quantity}, 201)

    def order(self, items):
        return self.request("/api/orders", "POST", {
            "customerId": str(uuid.uuid4()), "totalAmount": 10, "currency": "USD",
            "items": [{"sku": sku, "quantity": quantity} for sku, quantity in items]}, 201)["id"]

    def wait_order(self, order_id, status="COMPLETED"):
        # Saga endpoint reads PostgreSQL directly, avoiding a cached pending response.
        eventually(f"Saga {order_id} becomes {status}",
                   lambda: self.request(f"/api/orders/{order_id}/saga")["state"] == status)
        require(self.sql("orderflow", f"SELECT status FROM orders WHERE id='{order_id}'") == status,
                "Order and Saga states differ")

    def assert_stock(self, sku, quantity):
        actual = self.sql("inventory_db", f"SELECT quantity || ':' || reserved_quantity FROM inventory WHERE sku='{sku}'")
        require(actual == f"{quantity}:0", f"Unexpected stock for {sku}: {actual}")

    def payload(self, database, order_id, event_type):
        return self.sql(database, f"SELECT payload FROM outbox_events WHERE aggregate_id='{order_id}' AND event_type='{event_type}' ORDER BY created_at LIMIT 1")

    def group_offsets(self, group, topic):
        output = self.kafka("kafka-consumer-groups", "--describe", "--group", group)
        rows = re.findall(rf"{re.escape(group)}\s+{re.escape(topic)}\s+\d+\s+(-|\d+)\s+(\d+)\s+(-|\d+)", output)
        require(len(rows) == 3, f"Expected three assigned partitions: {output}")
        # Kafka prints '-' for a completely empty partition with no committed offset.
        require(all((current == "-" and end == "0" and lag == "-")
                    or (current != "-" and lag == "0") for current, end, lag in rows),
                "Consumer still has lag")
        return sum(int(row[0]) for row in rows if row[0] != "-")

    def replay(self, topic, order_id, payload, group):
        before = eventually("consumer caught up before replay", lambda: self.group_offsets(group, topic) or -1)
        self.kafka("kafka-console-producer", "--topic", topic, "--property", "parse.key=true",
                   "--property", "key.separator=|", data=f"{order_id}|{payload}\n{order_id}|{payload}\n")
        eventually("duplicate events committed", lambda: self.group_offsets(group, topic) >= before + 2)

    def external_kafka(self):
        # Metadata API v4 returns the advertised broker address (not just an open port).
        client = b"orderflow-smoke"
        request = struct.pack(">hhih", 3, 4, 1, len(client)) + client + struct.pack(">i?", 0, False)
        with socket.create_connection(("127.0.0.1", self.ports["KAFKA_PORT"]), timeout=10) as connection:
            def receive(size):
                result = b""
                while len(result) < size:
                    chunk = connection.recv(size - len(result))
                    require(bool(chunk), "Kafka closed metadata connection")
                    result += chunk
                return result
            connection.sendall(struct.pack(">i", len(request)) + request)
            response = receive(struct.unpack(">i", receive(4))[0])
        correlation, _, count = struct.unpack_from(">iii", response)
        require(correlation == 1 and count == 1, "Unexpected Kafka metadata")
        host_length = struct.unpack_from(">h", response, 16)[0]
        host = response[18:18 + host_length].decode()
        port = struct.unpack_from(">i", response, 18 + host_length)[0]
        require((host, port) == ("localhost", self.ports["KAFKA_PORT"]), f"Wrong external listener: {host}:{port}")
        self.passed("Kafka external listener metadata", host=host, port=port)

    def run(self):
        print(f"Project: {self.project}; evidence: {self.report_dir}", flush=True)
        print("Building/starting seven containers...", flush=True)
        self.up(build=not self.args.skip_build)
        rows = self.compose("ps", "--all", "--format", "json")
        # Compose versions emit either a JSON array or one object per line.
        rows = json.loads(rows) if rows.startswith("[") else [json.loads(row) for row in rows.splitlines()]
        require({row["Service"] for row in rows} == set(SERVICES), "Missing Compose services")
        require(all(row.get("Health") == "healthy" for row in rows), "A container is not healthy")
        for service in SERVICES[3:]:
            version = self.compose("exec", "-T", service, "java", "-version")
            require('version "21.' in version, f"{service} does not use Java 21")
            require(self.compose("exec", "-T", service, "id", "-u") == "10001", f"{service} runs as root")
        self.passed("Seven healthy containers, Java 21 and non-root runtime")
        self.external_kafka()
        def registrations():
            applications = self.request("/eureka/apps", port=self.ports["DISCOVERY_PORT"])["applications"]["application"]
            hosts = {instance["hostName"] for application in applications for instance in application["instance"]
                     if instance["status"] == "UP"}
            return hosts.issuperset({"order-service", "inventory-service", "gateway-service"})
        eventually("Eureka registers Docker DNS hostnames", registrations)
        sku = "SMOKE-" + uuid.uuid4().hex[:12]
        eventually("Gateway discovers Inventory via Eureka",
                   lambda: self.request(f"/api/inventory/{sku}", expected=404) or True)
        # Read-only probes also initialize each route's HTTP/Redis/JPA clients.
        # Never retry a POST whose response timed out: its transaction may have committed.
        missing_order = str(uuid.uuid4())
        eventually("Gateway discovers Order via Eureka",
                   lambda: self.request(f"/api/orders/{missing_order}", expected=404) or True)
        self.stock(sku)
        self.stock(sku + "-EMPTY", 0)
        self.passed("Gateway routes through Eureka and container DNS")

        order_id = self.order([(sku, 2)])
        self.wait_order(order_id)
        self.assert_stock(sku, 18)
        for database in ("orderflow", "inventory_db"):
            eventually("Outbox rows published", lambda database=database:
                       self.sql(database, f"SELECT count(*) FROM outbox_events WHERE aggregate_id='{order_id}' AND status='PUBLISHED'") == "2")
        require(self.sql("inventory_db", f"SELECT count(*) FROM inventory_reservations WHERE order_id='{order_id}' AND status='CONFIRMED'") == "1", "Reservation not confirmed")
        self.passed("Gateway -> Order -> Outbox -> Kafka -> Inventory -> Saga", orderId=order_id)

        rejected = self.order([(sku, 1), (sku + "-EMPTY", 1)])
        self.wait_order(rejected, "CANCELLED")
        self.assert_stock(sku, 18)
        require(self.sql("inventory_db", f"SELECT state FROM inventory_workflows WHERE order_id='{rejected}'") == "RELEASED", "Compensation incomplete")
        self.passed("Multi-SKU rejection and Saga compensation", orderId=rejected)

        created = self.payload("orderflow", order_id, "ORDER_CREATED")
        confirmed = self.payload("inventory_db", order_id, "INVENTORY_CONFIRMED")
        self.replay("order-events", order_id, created, "inventory-service")
        self.replay("inventory-events", order_id, confirmed, "order-service")
        for database, table, payload in (("inventory_db", "processed_events", created),
                                          ("orderflow", "processed_inventory_events", confirmed)):
            event_id = json.loads(payload)["eventId"]
            require(self.sql(database, f"SELECT count(*) FROM {table} WHERE event_id='{event_id}'") == "1", "Duplicate inbox record")
        self.assert_stock(sku, 18)
        self.wait_order(order_id)
        self.passed("Duplicate Kafka commands/results committed with one business effect")

        self.request(f"/api/orders/{order_id}")
        self.request(f"/api/orders/{order_id}")
        self.request(f"/api/inventory/{sku}")
        self.request(f"/api/inventory/{sku}")
        for key, maximum in ((f"orderflow:order:v1:{order_id}", 45), (f"orderflow:inventory:v1:{sku}", 5)):
            require(0 < int(self.redis("TTL", key)) <= maximum, f"Wrong cache TTL for {key}")
        require(json.loads(self.redis("GET", f"orderflow:order:v1:{order_id}"))["status"] == "COMPLETED", "Stale order cache")
        self.passed("Redis caches contain completed order and inventory with bounded TTL")

        self.compose("stop", "redis")
        self.request(f"/api/orders/{order_id}")
        self.request(f"/api/inventory/{sku}")
        fallback_order = self.order([(sku, 1)])
        self.wait_order(fallback_order)
        self.assert_stock(sku, 17)
        self.compose("start", "redis")
        eventually("Redis recovery", lambda: self.redis("PING") == "PONG")
        eventually("Inventory cache converges after Redis recovery",
                   lambda: self.request(f"/api/inventory/{sku}")["quantity"] == 17)
        self.passed("Redis outage database fallback and Saga continuity")

        self.compose("stop", "kafka")
        pending = self.order([(sku, 3)])
        eventually("Outbox records a failed publish", lambda:
                   int(self.sql("orderflow", f"SELECT COALESCE(max(retry_count),0) FROM outbox_events WHERE aggregate_id='{pending}'")) > 0)
        require(self.sql("orderflow", f"SELECT count(*) FROM outbox_events WHERE aggregate_id='{pending}' AND status='PUBLISHED'") == "0", "Outbox published without broker")
        self.compose("start", "kafka")
        self.wait_order(pending)
        self.assert_stock(sku, 14)
        self.passed("Kafka outage persists Outbox retry and recovers automatically", orderId=pending)

        self.compose("stop", "inventory-service")
        delayed = self.order([(sku, 1)])
        eventually("Command published while consumer stopped", lambda:
                   self.sql("orderflow", f"SELECT count(*) FROM outbox_events WHERE aggregate_id='{delayed}' AND status='PUBLISHED'") == "1")
        self.compose("restart", "order-service")
        self.compose("start", "inventory-service")
        self.wait_order(delayed)
        self.assert_stock(sku, 13)
        eventually("Inventory HTTP route after service restart",
                   lambda: self.request(f"/api/inventory/{sku}")["quantity"] == 13)
        self.passed("Service restart resumes durable Saga and retained Kafka command", orderId=delayed)

        self.compose("pause", "inventory-service")
        self.paused.add("inventory-service")
        try:
            response = self.request(f"/api/inventory/{sku}", expected=504)
            require(response["code"] == "DOWNSTREAM_TIMEOUT", "Wrong timeout fallback")
            opened = False
            for _ in range(25):
                try:
                    response = self.request(f"/api/inventory/{sku}", expected=503)
                    if response["code"] == "CIRCUIT_OPEN":
                        opened = True
                        break
                except AssertionError:
                    pass  # Before opening, real requests still time out with 504.
            require(opened, "Inventory circuit did not open")
            self.request(f"/api/orders/{order_id}")
            asynchronous = self.order([(sku, 1)])
            self.passed("Day12 timeout, CIRCUIT_OPEN and independent Order route")
        finally:
            self.compose("unpause", "inventory-service")
            self.paused.discard("inventory-service")
        eventually("Inventory circuit half-open recovery", lambda: self.request(f"/api/inventory/{sku}") or True)
        for _ in range(3):
            self.request(f"/api/inventory/{sku}")
        self.wait_order(asynchronous)
        self.assert_stock(sku, 12)
        self.passed("Day12 circuit recovery and Kafka Saga continuity")

        container = self.compose("ps", "-q", "order-service")
        def restarts():
            return int(subprocess.check_output(["docker", "inspect", "-f", "{{.RestartCount}}", container], text=True).strip())
        before = restarts()
        # docker-init is PID 1; its sole child is the JVM. Crashing the JVM tests the
        # Engine restart policy without marking the container manually stopped.
        try:
            self.compose("exec", "-T", "order-service", "sh", "-c", "kill -9 $(cat /proc/1/task/1/children)")
        except RuntimeError:
            pass  # exec may lose its connection as the JVM/container exits.
        eventually("Engine restarts crashed JVM", lambda: restarts() > before)
        eventually("Crashed Order recovers", lambda: self.request(f"/api/orders/{order_id}") or True)
        self.passed("unless-stopped restarts a crashed JVM", restartCount=restarts())

        order_count = self.sql("orderflow", "SELECT count(*) FROM orders")
        for database in ("orderflow", "inventory_db"):
            eventually("All Outbox rows settled before recreation", lambda database=database:
                       self.sql(database, "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED'") == "0")
        offsets = sorted(self.kafka("kafka-get-offsets", "--topic", "order-events").splitlines())
        self.redis("SET", "orderflow:smoke:persistence", self.project)
        self.compose("down", timeout=180)  # Deliberately retain the three data volumes.
        self.up()
        require(self.sql("orderflow", "SELECT count(*) FROM orders") == order_count, "Orders lost after container recreation")
        self.assert_stock(sku, 12)
        require(self.redis("GET", "orderflow:smoke:persistence") == self.project, "Redis AOF data lost")
        require(sorted(self.kafka("kafka-get-offsets", "--topic", "order-events").splitlines()) == offsets, "Kafka topic offsets lost")
        self.wait_order(order_id)
        self.wait_order(delayed)
        eventually("Order HTTP read after recreation", lambda: self.request(f"/api/orders/{order_id}") or True)
        eventually("Inventory HTTP read after recreation",
                   lambda: self.request(f"/api/inventory/{sku}")["quantity"] == 12)
        self.passed("Container recreation preserves PostgreSQL, Redis AOF and Kafka log", orderCount=int(order_count))
        after_recreate = self.order([(sku, 1)])
        self.wait_order(after_recreate)
        self.assert_stock(sku, 11)
        self.passed("Full workflow succeeds after persistent-volume recovery", orderId=after_recreate)

    def finish(self, error=None):
        report = {"project": self.project, "status": "FAIL" if error else "PASS", "checks": self.checks,
                  "startedAt": self.started.isoformat(), "finishedAt": datetime.now(timezone.utc).isoformat(),
                  "error": None if error is None else str(error), "ports": self.ports}
        (self.report_dir / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        for service in list(self.paused):
            self.compose("unpause", service)
        try:
            logs = self.compose("logs", "--no-color", "--timestamps", timeout=90)
            (self.report_dir / "services.log").write_text(logs, encoding="utf-8")
        finally:
            if self.args.keep:
                print(f"Retained {self.project}; ports are in report.json. Cleanup: docker compose -p {self.project} down --volumes", flush=True)
            else:
                self.compose("down", "--volumes", "--remove-orphans", timeout=180)
        print(f"{report['status']}: {len(self.checks)} checks; {self.report_dir / 'report.json'}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Use already built images")
    parser.add_argument("--keep", action="store_true", help="Keep this disposable project for diagnosis")
    parser.add_argument("--image-prefix", default="orderflow")
    parser.add_argument("--image-tag", default="local")
    args = parser.parse_args()
    smoke = Smoke(args)
    error = None
    try:
        smoke.run()
    except (Exception, KeyboardInterrupt) as failure:
        error = failure
        print(f"FAIL: {failure}", flush=True)
    finally:
        try:
            smoke.finish(error)
        except Exception as cleanup_error:
            print(f"Cleanup failed: {cleanup_error}", flush=True)
            error = error or cleanup_error
    return 1 if error else 0


if __name__ == "__main__":
    raise SystemExit(main())
