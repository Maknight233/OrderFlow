"""Generate the final Day11 report only from passing, current test evidence."""
from datetime import datetime
from pathlib import Path
import json
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
day11 = root / "integration-tests/target/day11-reports"
day10 = root / "integration-tests/target/day10-reports"
cache_checks = json.loads((day11 / "checks.json").read_text(encoding="utf-8-sig"))
regression_checks = json.loads((day10 / "checks.json").read_text(encoding="utf-8-sig"))
if datetime.fromisoformat(regression_checks["generatedAt"]) < datetime.fromisoformat(cache_checks["generatedAt"]):
    raise SystemExit("Run Day9/Day10 regression after the Day11 cache suite before generating the final summary.")

specs = [
    (day11, "com.rom.orderflow.order.cache.OrderReadCacheTest", "Order cache unit/configuration"),
    (day11, "com.rom.orderflow.inventory.cache.InventoryReadCacheTest", "Inventory cache unit/configuration"),
    (day11, "com.rom.orderflow.inventory.cache.InventoryCacheInvalidationTest", "Inventory mutation wiring"),
    (day11, "com.rom.orderflow.inventory.cache.InventoryRedisIntegrationTest", "Real Redis cache"),
    (day11, "com.rom.orderflow.integration.RedisCacheIntegrationTest", "HTTP/Kafka/Redis end-to-end"),
    (day10, "com.rom.orderflow.integration.SagaWorkflowIntegrationTest", "Day9 regression"),
    (day10, "com.rom.orderflow.integration.ObservabilityIntegrationTest", "Day10 regression"),
]
suites = []
for directory, name, label in specs:
    report = directory / f"TEST-{name}.xml"
    suite = ET.parse(report).getroot()
    counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
    if counts["tests"] == 0 or any(counts[key] for key in ("failures", "errors", "skipped")):
        raise SystemExit(f"Cannot mark Day11 complete: {label}: {counts}")
    suites.append((report, suite, label, counts["tests"]))
total = sum(count for _, _, _, count in suites)
lines = [
    "# Day 11 final test summary", "",
    f"Generated: {datetime.now().astimezone().isoformat(timespec='seconds')}",
    f"Day11 validation completed: {cache_checks['generatedAt']}",
    f"Day9/Day10 regression completed: {regression_checks['generatedAt']}", "",
    f"Result: **{total}/{total} passed**, failures: 0, errors: 0, skipped: 0.", "",
    "## Completed in requested order", "",
    "- [x] GET `/api/orders/{id}` uses an optional Redis cache; PostgreSQL remains authoritative.",
    "- [x] Order cache defaults to **45 seconds** (`ORDER_CACHE_TTL`); inventory cache remains 5 seconds.",
    "- [x] Saga transitions register order cache eviction after transaction commit, including Kafka results,",
    "  HTTP cancellation and scheduled timeout compensation. Rolled-back transactions do not evict.",
    "- [x] Run real Gateway HTTP/PostgreSQL/Kafka/Redis end-to-end tests.",
    "- [x] Re-run Day9 Saga regression, then Day10 observability regression.",
    "- [x] Update this final summary, Day11 runbook and README.", "",
    f"Service build: **{cache_checks['serviceBuild']}**; Prometheus configuration: **{regression_checks['prometheusConfig']}**;",
    f"Prometheus alert rule tests: **{regression_checks['alertRuleTests']}**.", "",
    "| Suite | Passed | Seconds |", "|---|---:|---:|",
]
for _, suite, label, count in suites:
    lines.append(f"| {label} | {count}/{count} | {float(suite.get('time', '0')):.3f} |")
lines += ["", "## End-to-end scenarios", "", "| Scenario | Result | Seconds |", "|---|---|---:|"]
for _, suite, _, _ in suites:
    if suite.get("name", "").endswith("RedisCacheIntegrationTest"):
        for case in suite.findall("testcase"):
            lines.append(f"| {case.get('name')} | PASS | {float(case.get('time', '0')):.3f} |")
lines += [
    "", "## Environment and evidence", "",
    "- Java 21, Spring Boot 4.1.1, Redis 7, PostgreSQL 16 and Kafka 4.2.1.",
    "- Packaged Order, Inventory and Gateway JVMs; real HTTP/JDBC/Kafka/Redis. Gateway uses direct test routes;",
    "  Eureka registration and production load balancing are outside these suites.",
    "- Disposable projects `orderflow-it` (PostgreSQL 15432, Kafka 19092), `orderflow-day11` (Redis 16379),",
    "  and `orderflow-day10` (monitoring). Development project `orderflow` is not reset or used by tests.",
    "- Actual 45-second expiration is awaited. The separate restart test extends only its Redis fixture",
    "  to five minutes during cold startup, then restores 45 seconds; application TTL configuration stays 45s.",
    "- Order rollback is injected with a PostgreSQL trigger. DLT is observed by order ID, then replayed;",
    "  rollback preserves the cache and commits neither the inbox record nor the confirmation Outbox command.",
    "- Redis failure is injected by stopping the dedicated container. Database-backed reads and Kafka Saga",
    "  completion continue; readiness/liveness stay UP, overall health reports the cache dependency failure.",
    "- Cache corruption/wrong-ID payloads, cache hit metrics, HTTP mutation invalidation, Kafka compensation",
    "  and scheduled timeout invalidation are exercised. Inventory recovers fresh values after outage expiration.",
    "- Service JSON logs: `integration-tests/target/service-logs/run-*/`; runner logs: `integration-tests/target/day11-run.log`",
    "  and `integration-tests/target/day11-regression-run.log`.", "",
    "XML reports used for this summary:", "",
]
lines += [f"- `{report.relative_to(root).as_posix()}`" for report, _, _, _ in suites]
lines += [
    "", "## Consistency limits", "",
    "This is an eventually consistent read cache. A concurrent database read can repopulate an old value",
    "after another transaction evicts it. Failed eviction, direct SQL writes and Redis persistence can also",
    "leave an older cached response. TTL bounds each stored entry's lifetime, not freshness from a commit.",
    "Saga/stock decisions always read PostgreSQL. Disable the relevant cache for authoritative HTTP reads.",
    "Version fencing, cache stampede mitigation, load testing and dedicated cache dashboard panels remain future work.", "",
    "## Reproduction", "",
    "```powershell",
    "powershell -File scripts/run-day11-tests.ps1",
    "powershell -File scripts/run-day10-tests.ps1 -RefreshDay9Summary",
    "python scripts/summarize-day11.py",
    "```", "",
    "[Day11 runbook](day11-redis-cache.md), [Day9 regression summary](day9-integration-test-summary.md),",
    "[Day10 regression summary](day10-observability-summary.md).", "",
]
output = root / "docs/day11-redis-cache-summary.md"
output.write_text("\n".join(lines), encoding="utf-8")
print(f"{total}/{total} passed; failures=0; errors=0; skipped=0")
print(output)
