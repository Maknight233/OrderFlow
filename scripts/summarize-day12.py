"""Generate Day12 delivery evidence after fresh, passing Day9-Day11 regressions."""
from datetime import datetime
from pathlib import Path
import json
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
reports = root / "integration-tests/target"
day12 = reports / "day12-reports"
checks = json.loads((day12 / "checks.json").read_text(encoding="utf-8-sig"))
cache_checks = json.loads((reports / "day11-reports/checks.json").read_text(encoding="utf-8-sig"))
reg_checks = json.loads((reports / "day10-reports/checks.json").read_text(encoding="utf-8-sig"))
if not (datetime.fromisoformat(checks["generatedAt"]) < datetime.fromisoformat(cache_checks["generatedAt"]) < datetime.fromisoformat(reg_checks["generatedAt"])):
    raise SystemExit("Run Day11 and Day9/Day10 regressions after the Day12 suites.")
specs = [
    (day12, "com.rom.orderflow.gateway.resilience.GatewayConcurrencyFilterTest", "Day12 concurrency unit"),
    (day12, "com.rom.orderflow.integration.GatewayResilienceIntegrationTest", "Day12 real HTTP faults"),
    (day12, "com.rom.orderflow.integration.ResilienceSagaIntegrationTest", "Day12 real Saga/fault proxies"),
    (reports / "day11-reports", "com.rom.orderflow.order.cache.OrderReadCacheTest", "Day11 order cache"),
    (reports / "day11-reports", "com.rom.orderflow.inventory.cache.InventoryReadCacheTest", "Day11 inventory cache"),
    (reports / "day11-reports", "com.rom.orderflow.inventory.cache.InventoryCacheInvalidationTest", "Day11 mutation wiring"),
    (reports / "day11-reports", "com.rom.orderflow.inventory.cache.InventoryRedisIntegrationTest", "Day11 real Redis"),
    (reports / "day11-reports", "com.rom.orderflow.integration.RedisCacheIntegrationTest", "Day11 HTTP/Kafka/Redis"),
    (reports / "day10-reports", "com.rom.orderflow.integration.SagaWorkflowIntegrationTest", "Day9 Saga regression"),
    (reports / "day10-reports", "com.rom.orderflow.integration.ObservabilityIntegrationTest", "Day10 observability + Day12 dashboard"),
]
suites = []
for directory, name, label in specs:
    path = directory / f"TEST-{name}.xml"
    suite = ET.parse(path).getroot()
    count = int(suite.get("tests", "0"))
    if not count or any(int(suite.get(key, "0")) for key in ("failures", "errors", "skipped")):
        raise SystemExit(f"Failed/incomplete evidence: {path}")
    suites.append((path, suite, label, count))
total = sum(count for _, _, _, count in suites)
lines = ["# Day 12 resilience test summary", "",
         f"Generated: {datetime.now().astimezone().isoformat(timespec='seconds')}",
         f"Day12 verification completed: {checks['generatedAt']}",
         f"Day9-Day11 regression completed: {reg_checks['generatedAt']}", "",
         f"Result: **{total}/{total} passed**, failures: 0, errors: 0, skipped: 0.", "",
         "All required development/verification tasks are implemented; Git delivery is recorded in repository history.",
         "Rate limiting is optional and deferred, as requested; no 429/quota guarantee is claimed.", "",
         f"Service build: **{checks['serviceBuild']}**; Prometheus configuration: **{reg_checks['prometheusConfig']}**;",
         f"Prometheus alert rule tests: **{reg_checks['alertRuleTests']}**.", "",
         "| Suite | Passed | Seconds |", "|---|---:|---:|"]
for _, suite, label, count in suites:
    lines.append(f"| {label} | {count}/{count} | {float(suite.get('time', '0')):.3f} |")
lines += ["", "## Day12 fault scenarios", "", "| Scenario | Result | Seconds |", "|---|---|---:|"]
for directory, suite, _, _ in suites[:3]:
    for case in suite.findall("testcase"):
        lines.append(f"| {case.get('name')} | PASS | {float(case.get('time', '0')):.3f} |")
lines += ["", "## Evidence and operating limits", "",
    "- Packaged Gateway JVM and controlled real HTTP servers verify circuit states, ten-failure threshold,",
    "  ten-second OPEN recovery window, three HALF_OPEN probes, bounded retries/backoff, POST no-replay",
    "  (including connection reset), timeouts, concurrency rejection and Prometheus metrics.",
    "- Real Order/Inventory/PostgreSQL/Kafka/Redis and HTTP fault proxies verify one order/reservation/stock",
    "  deduction after a committed POST response is lost, duplicate Kafka-result idempotency, HTTP Inventory",
    "  circuit isolation from Kafka Saga processing and actual Order Service stop/restart recovery.",
    "- Day11 regressions exercise Redis outage and recovery; Day9/Day10 cover Kafka/database failure,",
    "  compensation, Outbox, DLT, recovery, tracing, metrics and alerts. Their original scenario counts remain unchanged.",
    "- Live Grafana API validates the new six-panel resilience dashboard and the existing twelve-panel dashboard.",
    "- Development project orderflow is not reset. Disposable orderflow-it/orderflow-day11/orderflow-day10",
    "  projects and service JVMs are cleaned up. Builds and suites run sequentially.",
    "- IntelliJ debugger router tools were unavailable. Runtime diagnostics used actual HTTP attempt counts",
    "  and exported circuit metrics; timeout exception matching was checked against Gateway 5.0.3 source.",
    "- No POST automatic retry or business status mutation by fallback. A 503/504 after an upstream commit",
    "  remains an ambiguous write outcome, not proof of rollback; client idempotency keys are outside this release.",
    "- Circuit state and concurrency limits are per Gateway instance. Streaming responses already committed",
    "  cannot be replaced with fallback JSON. Eureka discovery/load balancing, load tests and distributed rate limiting are not covered.", "",
    "## Reports and reproduction", "",
    "```powershell", "powershell -File scripts/run-day12-tests.ps1 -RunRegressions", "```", "",
    "Raw XML evidence:", ""]
lines += [f"- `{path.relative_to(root).as_posix()}`" for path, _, _, _ in suites]
lines += ["", "Service logs/metrics: `integration-tests/target/service-logs/`.",
          "[Day12 runbook](day12-resilience.md), [Day11 regression summary](day11-redis-cache-summary.md),",
          "[Day9 summary](day9-integration-test-summary.md), [Day10 summary](day10-observability-summary.md).", ""]
output = root / "docs/day12-resilience-summary.md"
output.write_text("\n".join(lines), encoding="utf-8")
print(f"{total}/{total} passed; failures=0; errors=0; skipped=0")
print(output)
