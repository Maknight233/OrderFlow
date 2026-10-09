"""Generate a summary from the actual Surefire XML report."""
from datetime import datetime
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
report = root / "integration-tests/target/surefire-reports/TEST-com.rom.orderflow.integration.SagaWorkflowIntegrationTest.xml"
suite = ET.parse(report).getroot()
cases = suite.findall("testcase")
failures = int(suite.get("failures", "0"))
errors = int(suite.get("errors", "0"))
skipped = int(suite.get("skipped", "0"))
tests = int(suite.get("tests", "0"))
passed = tests - failures - errors - skipped
duration = float(suite.get("time", "0"))
lines = [
    "# Day 9 integration test summary", "",
    f"Generated: {datetime.now().astimezone().isoformat(timespec='seconds')}",
    f"Suite timestamp: {suite.get('timestamp', 'not recorded')}", "",
    f"Result: **{passed}/{tests} passed**, failures: {failures}, errors: {errors}, skipped: {skipped}.",
    f"Suite elapsed time: **{duration:.3f} seconds** (includes service startup and restart).", "",
    "Environment: Java 21, Spring Boot 4.1.1, PostgreSQL 16, Kafka 4.2.1.",
    "Two real packaged service processes; real HTTP, JDBC, Kafka and Flyway. No business mocks.",
    "Dedicated Docker Compose project orderflow-it: PostgreSQL 15432, Kafka 19092, services 18081/18082.",
    "Development containers and databases were not used for these tests.", "",
    "| Scenario | Result | Seconds |",
    "|---|---|---:|",
]
issues = []
for case in cases:
    status = "PASS"
    detail = None
    for tag, label in (("failure", "FAIL"), ("error", "ERROR"), ("skipped", "SKIP")):
        detail = case.find(tag)
        if detail is not None:
            status = label
            break
    name = case.get("name", "").replace("|", "\\|")
    lines.append(f"| {name} | {status} | {float(case.get('time', '0')):.3f} |")
    if status in ("FAIL", "ERROR"):
        message = " ".join(detail.get("message", detail.text or "").split())[:800]
        issues.append(f"- {name}: {message}")
if issues:
    lines += ["", "## Failed cases", "", *issues]
lines += [
    "", "## Runtime defect found and fixed", "",
    "The first startup attempt failed because Spring Boot's automatic KafkaTemplate",
    "required ProducerFactory<Object,Object>, while the custom factory was typed",
    "ProducerFactory<String,Object>. Both services now explicitly declare the matching",
    "KafkaTemplate. The final test run exercises startup and real publishing.", "",
    "Legacy OrderCreated payloads also contain status=PENDING. Type inference now",
    "checks items before interpreting a legacy status as an inventory result;",
    "three additional regression cases cover old order and inventory payloads.", "",
    "## Scope and controls", "",
    "- Covers fresh and legacy migrations, multi-SKU completion, duplicate SKU merging,",
    "  insufficient/missing inventory rollback, duplicate events, reordered cancellation,",
    "  confirmation/cancellation races, concurrent stock contention, result Outbox failure,",
    "  broker outage, transaction failure and DLT replay, invalid JSON in both directions,",
    "  timeout/manual recovery, process crash/stale claims, failed command retry,",
    "  PostgreSQL SKIP LOCKED, and request validation.",
    "- Uses five Outbox attempts (three for intentional result-publishing exhaustion)",
    "  and two Saga recovery retries. Polling is 200ms and claim expiry is 3 seconds.",
    "  Normal Saga deadlines are 600 seconds so service startup does not trigger unrelated",
    "  compensation. Consumer session timeout is 6 seconds; service readiness allows 300 seconds.",
    "- Timeout/deadline, exhausted compensation, and abandoned claims are injected by",
    "  updating only dedicated test database rows. No multi-minute clock wait is required.",
    "- Multi-SKU transaction failure is injected by a PostgreSQL trigger on result Outbox",
    "  insertion. Broker outage is a real stop/start of the dedicated Kafka container.",
    "- Crash tests forcibly terminate dedicated JVMs; recovery uses the same database.",
    "- Gateway/Eureka, external payment/shipping, multi-broker failover, and load testing",
    "  are outside this suite. Existing service tests tied to development databases",
    "  are not included in these counts.", "",
    "## Artifacts and reproduction", "",
    "- XML: integration-tests/target/surefire-reports/TEST-com.rom.orderflow.integration.SagaWorkflowIntegrationTest.xml",
    "- Text report: integration-tests/target/surefire-reports/com.rom.orderflow.integration.SagaWorkflowIntegrationTest.txt",
    "- Service logs: integration-tests/target/service-logs/",
    "- Run: powershell -File scripts/run-integration-tests.ps1",
    "- If needed, pass -MavenRepository C:/Users/marj-/.m2/repository.",
    "- Summarize: python scripts/summarize-integration-tests.py", "",
]
output = root / "docs/day9-integration-test-summary.md"
output.write_text("\n".join(lines), encoding="utf-8")
print(f"{passed}/{tests} passed; failures={failures}; errors={errors}; skipped={skipped}; seconds={duration:.3f}")
print(output)
