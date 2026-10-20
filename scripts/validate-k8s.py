"""Render Kustomize, validate Kubernetes schemas and check local references.

Never queries a cluster or loads real Secrets. The two external Secret contracts
are fulfilled by set-k8s-secrets.ps1 before deployment.
"""

import argparse
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXTERNAL_SECRETS = {
    "postgres-credentials": {"POSTGRES_PASSWORD"},
    "ghcr-auth": {".dockerconfigjson"},
}


class UniqueKeyLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    loader.flatten_mapping(node)
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise ValueError(f"Duplicate YAML key {key!r} at line {key_node.start_mark.line + 1}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_resources(resources):
    objects = {}
    for resource in resources:
        kind, meta = resource["kind"], resource["metadata"]
        name = meta["name"]
        require((kind, name) not in objects, f"Duplicate resource: {kind}/{name}")
        require(kind != "Secret", "Real Secret resources must stay outside k8s/base")
        if kind == "Namespace":
            require(name == "orderflow" and "namespace" not in meta, "Invalid Namespace")
        else:
            require(meta.get("namespace") == "orderflow", f"Wrong namespace: {kind}/{name}")
        objects[kind, name] = resource
    require(("Namespace", "orderflow") in objects, "Namespace missing")

    def config(name, key=None):
        require(("ConfigMap", name) in objects, f"Missing ConfigMap {name}")
        data = objects["ConfigMap", name].get("data", {})
        require(key is None or key in data, f"Missing ConfigMap key: {name}/{key}")

    def secret(name, key=None):
        require(name in EXTERNAL_SECRETS, f"Unknown external Secret {name}")
        require(key is None or key in EXTERNAL_SECRETS[name], f"Invalid Secret key: {name}/{key}")

    workloads = [r for r in resources if r["kind"] in {"Deployment", "StatefulSet"}]
    require(len(workloads) == 7, "Expected four Deployments and three StatefulSets")
    for resource in workloads:
        spec, name = resource["spec"], resource["metadata"]["name"]
        labels = spec["template"]["metadata"]["labels"]
        require(all(labels.get(k) == v for k, v in spec["selector"]["matchLabels"].items()), f"Selector mismatch: {name}")
        require(spec["replicas"] == 1, f"Demo workload must start with one replica: {name}")
        pod = spec["template"]["spec"]
        require(pod.get("automountServiceAccountToken") is False, f"API token mounted: {name}")
        require(pod["securityContext"]["runAsNonRoot"], f"Root Pod: {name}")
        volumes = {v["name"] for v in pod.get("volumes", [])}
        volumes.update(v["metadata"]["name"] for v in spec.get("volumeClaimTemplates", []))
        if resource["kind"] == "StatefulSet":
            service = objects.get(("Service", spec["serviceName"]))
            require(service and service["spec"].get("clusterIP") == "None", f"Missing governing headless Service: {name}")
            require(spec["volumeClaimTemplates"], f"Missing persistent storage: {name}")
        for volume in pod.get("volumes", []):
            if "configMap" in volume:
                ref = volume["configMap"]
                config(ref["name"])
                for item in ref.get("items", []):
                    config(ref["name"], item["key"])
            if "secret" in volume:
                secret(volume["secret"]["secretName"])
        for ref in pod.get("imagePullSecrets", []):
            secret(ref["name"], ".dockerconfigjson")
        for container in pod.get("initContainers", []) + pod["containers"]:
            context = f"{name}/{container['name']}"
            security = container["securityContext"]
            require(security.get("allowPrivilegeEscalation") is False, f"Privilege escalation: {context}")
            require("ALL" in security.get("capabilities", {}).get("drop", []), f"Capabilities retained: {context}")
            for bound in ("requests", "limits"):
                require({"cpu", "memory"} <= container["resources"][bound].keys(), f"Missing resource bound: {context}")
            for mount in container.get("volumeMounts", []):
                require(mount["name"] in volumes, f"Missing volume: {context}/{mount['name']}")
            for ref in container.get("envFrom", []):
                if "configMapRef" in ref:
                    config(ref["configMapRef"]["name"])
                if "secretRef" in ref:
                    secret(ref["secretRef"]["name"])
            for env in container.get("env", []):
                source = env.get("valueFrom", {})
                for field, check in (("configMapKeyRef", config), ("secretKeyRef", secret)):
                    if field in source:
                        check(source[field]["name"], source[field]["key"])
            ports = {p["name"]: p["containerPort"] for p in container.get("ports", [])}
            for probe_name in ("startupProbe", "readinessProbe", "livenessProbe"):
                require(probe_name in container, f"Missing {probe_name}: {context}")
                probe = container[probe_name]
                for handler in ("httpGet", "tcpSocket"):
                    if handler in probe:
                        port = probe[handler]["port"]
                        require(port in ports or port in ports.values(), f"Invalid probe port: {context}/{probe_name}")

    services = [r for r in resources if r["kind"] == "Service"]
    for service in services:
        name, spec = service["metadata"]["name"], service["spec"]
        matches = [w for w in workloads if all(w["spec"]["template"]["metadata"]["labels"].get(k) == v for k, v in spec["selector"].items())]
        require(matches, f"Service has no matching workload: {name}")
        for workload in matches:
            ports = {p["name"]: p["containerPort"] for c in workload["spec"]["template"]["spec"]["containers"] for p in c.get("ports", [])}
            for port in spec["ports"]:
                target = port["targetPort"]
                require(target in ports or target in ports.values(), f"Invalid Service targetPort: {name}/{target}")
                resolved = ports.get(target, target)
                require(resolved == port["port"], f"Service port mismatch: {name}")

    # Independently compare the deployment references with the Day 15 evidence.
    evidence = (ROOT / "docs/day15-ghcr-summary.md").read_text(encoding="utf-8")
    commit = re.search(r"验收提交：`([a-f0-9]{40})`", evidence).group(1)
    approved = dict(re.findall(r"\| `(\w+-service)` \| `(sha256:[a-f0-9]{64})`", evidence))
    expected_ports = {"discovery-service": 8761, "gateway-service": 9000, "order-service": 8081, "inventory-service": 8082}
    for name, port in expected_ports.items():
        app = objects["Deployment", name]["spec"]["template"]["spec"]
        container = app["containers"][0]
        require(container["image"] == f"ghcr.io/maknight233/orderflow-{name}:sha-{commit}@{approved[name]}", f"Image differs from verified Day 15 release: {name}")
        require(container["ports"] == [{"name": "http", "containerPort": port}], f"Wrong Java port: {name}")
        require({"name": "ghcr-auth"} in app["imagePullSecrets"], f"GHCR authentication missing: {name}")
        require(container["readinessProbe"]["httpGet"]["path"] == "/actuator/health/readiness", f"Wrong readiness path: {name}")
        require(container["livenessProbe"]["httpGet"]["path"] == "/actuator/health/liveness", f"Wrong liveness path: {name}")
        env = {entry["name"]: entry for entry in container["env"]}
        for property_name, field_path in (("ip-address", "status.podIP"), ("instance-id", "metadata.name")):
            variable = "EUREKA_INSTANCE_" + property_name.replace("-", "").upper()
            require(env.get(variable, {}).get("valueFrom", {}).get("fieldRef", {}).get("fieldPath") == field_path, f"Invalid Eureka Downward API binding: {name}/{variable}")

    # Resolve every application dependency from ConfigMaps to a Service and port.
    def endpoint(host, port):
        service_name = host.split(".")[0]
        require(host == f"{service_name}.orderflow.svc.cluster.local", f"Unexpected DNS name: {host}")
        require(("Service", service_name) in objects, f"Missing DNS Service: {host}")
        require(port in [p["port"] for p in objects["Service", service_name]["spec"]["ports"]], f"Invalid DNS port: {host}:{port}")

    settings = objects["ConfigMap", "orderflow-config"]["data"]
    require(settings.get("EUREKA_INSTANCE_PREFERIPADDRESS") == "true", "Eureka must prefer the Pod IP")
    require(settings.get("SPRING_LIFECYCLE_TIMEOUTPERSHUTDOWNPHASE") == "30s", "Spring graceful shutdown timeout not configured")
    for key in ("EUREKA_URL", "ORDER_DB_URL", "INVENTORY_DB_URL"):
        url = urlsplit(settings[key].removeprefix("jdbc:"))
        endpoint(url.hostname, url.port)
    kafka_host, kafka_port = settings["KAFKA_BOOTSTRAP_SERVERS"].split(":")
    endpoint(kafka_host, int(kafka_port))
    endpoint(settings["REDIS_HOST"], int(settings["REDIS_PORT"]))
    kafka = objects["ConfigMap", "kafka-config"]["data"]
    broker = "kafka-0.kafka-headless.orderflow.svc.cluster.local"
    require(kafka["KAFKA_ADVERTISED_LISTENERS"] == f"INTERNAL://{broker}:29092", "Kafka advertised DNS mismatch")
    require(kafka["KAFKA_CONTROLLER_QUORUM_VOTERS"] == f"1@{broker}:29093", "Kafka KRaft quorum mismatch")
    require(objects["Service", "kafka-headless"]["spec"]["publishNotReadyAddresses"], "Kafka bootstrap DNS can deadlock")
    for resource in resources:
        if resource["kind"] == "ConfigMap":
            for key in resource["data"]:
                require(not re.search(r"PASSWORD|TOKEN|DOCKERCONFIG|CREDENTIAL", key, re.I), f"Sensitive ConfigMap key: {key}")
    return len(workloads), len(services)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kubectl", default="kubectl")
    parser.add_argument("--kubeconform", default="kubeconform")
    parser.add_argument("--kubernetes-version", default="1.35.0")
    args = parser.parse_args()
    for path in (ROOT / "k8s/base").glob("*.yaml"):
        list(yaml.load_all(path.read_text(encoding="utf-8"), Loader=UniqueKeyLoader))
    rendered = subprocess.run([args.kubectl, "kustomize", "k8s/base"], cwd=ROOT, check=True, capture_output=True, text=True, encoding="utf-8").stdout
    destination = ROOT / "target/day16/rendered.yaml"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(rendered, encoding="utf-8")
    resources = list(yaml.load_all(rendered, Loader=UniqueKeyLoader))
    workloads, services = validate_resources(resources)
    subprocess.run([args.kubeconform, "-strict", "-summary", "-kubernetes-version", args.kubernetes_version, str(destination)], cwd=ROOT, check=True)
    print(f"PASS: {len(resources)} resources, {workloads} workloads, {services} Services; references, ports, namespaces and Day 15 image digests match.")
    print("External Secrets required: postgres-credentials/POSTGRES_PASSWORD, ghcr-auth/.dockerconfigjson (values and cluster access not checked).")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, yaml.YAMLError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
