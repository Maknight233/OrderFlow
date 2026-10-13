"""Build the Day10 summary from fresh Surefire reports and runner check evidence."""
from datetime import datetime
from pathlib import Path
import json
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
reports = root / "integration-tests/target/day10-reports"
evidence = json.loads((reports / "checks.json").read_text(encoding="utf-8-sig"))
suite_headers = [ET.parse(reports / f"TEST-com.rom.orderflow.integration.{name}.xml").getroot()
                 for name in ("SagaWorkflowIntegrationTest", "ObservabilityIntegrationTest")]
counts = {key: sum(int(suite.get(key, "0")) for suite in suite_headers)
          for key in ("tests", "failures", "errors", "skipped")}
passed_count = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
elapsed = sum(float(suite.get("time", "0")) for suite in suite_headers)
lines = [
    "# Day 10 observability summary", "",
    f"Generated: {datetime.now().astimezone().isoformat(timespec='seconds')}",
    f"Verification completed: {evidence['generatedAt']}", "",
    f"Result: **{passed_count}/{counts['tests']} passed**, failures: {counts['failures']}, errors: {counts['errors']}, skipped: {counts['skipped']}.",
    f"Suite elapsed time: **{elapsed:.3f} seconds** (includes service startup/restarts).", "",
    "Scope: health probes, Prometheus business metrics, structured logs, durable",
    "HTTP/Kafka tracing, Grafana dashboards, Prometheus alerts and operator recovery.", "",
    "## Implemented", "",
    "- Order, Inventory and Gateway expose health, info and Prometheus endpoints.",
    "  Liveness checks process availability; business-service readiness also checks PostgreSQL.",
    "  Kafka failure makes overall health DOWN while readiness remains UP, allowing durable Outbox writes.",
    "- Database-backed gauges track Outbox status, retries and backlog age, Saga state and overdue steps.",
    "  Cached snapshots keep scrapes responsive during database outages; availability and age expose stale data.",
    "- Process counters/timers track publication outcome, retry attempts, acknowledged DLT publication,",
    "  committed Saga transitions, step timeouts, recovery retries and total terminal workflow duration.",
    "  Saga metrics are recorded after transaction commit; duplicate messages do not inflate completion metrics.",
    "- JSON logs include orderId, eventId, eventType, state, reason, traceId and spanId at workflow boundaries.",
    "- W3C traceparent is persisted on both Outboxes (Order V5, Inventory V6) and restored per attempt.",
    "  Gateway HTTP, delayed Kafka commands and inventory results share one trace, including after process crashes.",
    "- Local Compose stack provisions Prometheus, Grafana, Zipkin, Alertmanager and Blackbox Exporter.",
    "  Grafana provisions 12 panels and Prometheus/Zipkin data sources; eight alert rules include FAILED,",
    "  MANUAL_RECOVERY, new DLT messages, dependency failures, backlog and stale metric snapshots.", "",
    "## Executed validation", "",
    f"Service build: **{evidence['serviceBuild']}**.",
    f"Prometheus configuration: **{evidence['prometheusConfig']}**; alert rule tests: **{evidence['alertRuleTests']}**.", "",
]
total_tests = total_passed = total_failures = total_errors = total_skipped = 0
for suite_name, label in [
    ("SagaWorkflowIntegrationTest", "Day9 regression"),
    ("ObservabilityIntegrationTest", "Day10 observability"),
]:
    suite = ET.parse(reports / f"TEST-com.rom.orderflow.integration.{suite_name}.xml").getroot()
    tests, failures, errors, skipped = (int(suite.get(k, "0")) for k in ("tests", "failures", "errors", "skipped"))
    passed = tests - failures - errors - skipped
    total_tests += tests
    total_passed += passed
    total_failures += failures
    total_errors += errors
    total_skipped += skipped
    lines += [f"### {label}", "",
              f"**{passed}/{tests} passed**, failures: {failures}, errors: {errors}, skipped: {skipped}.",
              f"Elapsed: **{float(suite.get('time', '0')):.3f} seconds**, including process startup/restarts.", "",
              "| Scenario | Result | Seconds |", "|---|---|---:|"]
    for case in suite.findall("testcase"):
        status = "PASS"
        for tag, result in [("failure", "FAIL"), ("error", "ERROR"), ("skipped", "SKIP")]:
            if case.find(tag) is not None:
                status = result
                break
        name = case.get("name", "").replace("|", "\\|")
        lines.append(f"| {name} | {status} | {float(case.get('time', '0')):.3f} |")
    lines.append("")
lines += [
    f"Combined result: **{total_passed}/{total_tests} passed**, failures: {total_failures}, errors: {total_errors}, skipped: {total_skipped}.", "",
    "## Runtime regression found and fixed", "",
    "Enabling KafkaTemplate Observation initially added a synchronous KafkaAdmin.clusterId lookup before send.",
    "The existing unreachable-producer test no longer exhausted three attempts within its original 35 seconds.",
    "A dedicated JVM thread snapshot proved the publisher was waiting in KafkaAdmin.clusterId through",
    "KafkaTemplate.observeSend. Producer templates now omit optional cluster-ID enrichment; Observation,",
    "W3C header propagation and original send/retry timeouts remain enabled. The unchanged regression is included in the final results above.",
    "IntelliJ debugger router tools were unavailable; diagnosis used real service logs and jcmd Thread.print.",
    "Evidence: integration-tests/target/day10-diagnostic-reports/publisher-thread.txt.", "",
    "## Environment and controls", "",
    "- Java 21, Spring Boot 4.1.1, PostgreSQL 16, Kafka 4.2.1; packaged service JVMs and real HTTP/JDBC/Kafka.",
    "- Monitoring images: Prometheus 3.2.1, Grafana 11.6.0, Zipkin 3.5.1, Alertmanager 0.28.1, Blackbox 0.26.0.",
    "- Dedicated business project orderflow-it uses PostgreSQL 15432 and Kafka 19092; services use 18081/18082.",
    "  Day10 Gateway uses 19000 with direct test routes. Eureka registration/load balancing is not exercised.",
    "- Day9/Day10 processes explicitly disable Order/Inventory Redis caches and Redis health checks;",
    "  Redis HTTP/Kafka failure and invalidation behavior is validated separately by the Day11 suite.",
    "- Dedicated monitoring project orderflow-day10 uses Prometheus 19090, Grafana 13000, Zipkin 19411,",
    "  Alertmanager 19093 and Blackbox 19115. Development databases and containers are not reset.",
    "- Broker/database outages stop and restart dedicated containers; crash verification kills the dedicated order JVM.",
    "  FAILED/MANUAL_RECOVERY are injected only into disposable test rows; alerts reach real Alertmanager and resolve after retry.",
    "- Transaction rollback is injected with an order-database trigger; replay and duplicate delivery verify committed metrics.",
    "- Final build and test execution are sequential: Windows locks running packaged archives.", "",
    "## Limits and operating notes", "",
    "- Gauges reflect durable database state across restarts. Counters and timer histograms are process-local and reset on restart;",
    "  dashboards use rate/increase where appropriate. DLT metrics count acknowledged application publications, not total retained records.",
    "- Existing Outbox created_at columns store local timestamps without a zone. Backlog age uses the configured publisher timezone",
    "  (defaults to the JVM zone); instances sharing a database must use one timezone. Legacy rows have no traceparent and start new traces.",
    "- Scheduled timeout recovery starts a new diagnostic trace; correlate it with the original flow by orderId.",
    "- Local tracing samples 100%; Zipkin uses in-memory storage. Sampling and durable trace retention require deployment configuration.",
    "- Alertmanager's local receiver keeps alerts visible in its UI. External email/chat delivery is not configured or exercised.",
    "- Multi-broker failover, load testing, payment/shipping and production deployment are outside this validation.", "",
    "## Artifacts and reproduction", "",
    "- Run: `powershell -File scripts/run-day10-tests.ps1`.",
    "- Optional: `-MavenRepository C:/Users/marj-/.m2/repository` and `-KeepInfrastructure`.",
    "- Reports/check evidence: `integration-tests/target/day10-reports/`.",
    "- Service logs: `integration-tests/target/service-logs/run-*/`.",
    "- Regenerate this summary: `python scripts/summarize-day10.py`.",
    "- Runbook: [Day10 observability](day10-observability.md).",
    "- Day9's separate summary remains [available](day9-integration-test-summary.md).", "",
]
output = root / "docs/day10-observability-summary.md"
output.write_text("\n".join(lines), encoding="utf-8")
print(f"{total_passed}/{total_tests} passed; failures={total_failures}; errors={total_errors}; skipped={total_skipped}")
print(output)
