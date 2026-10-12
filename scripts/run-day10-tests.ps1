param(
    [string]$MavenRepository,
    [switch]$KeepInfrastructure
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$reportRoot = Join-Path $projectRoot 'integration-tests/target/day10-reports'
$mavenOptions = @()
if ($MavenRepository) { $mavenOptions += "-Dmaven.repo.local=$MavenRepository" }
$environmentNames = @('PROMETHEUS_PORT','GRAFANA_PORT','ZIPKIN_PORT','ALERTMANAGER_PORT','BLACKBOX_PORT','PROMETHEUS_CONFIG','GRAFANA_PASSWORD')
$savedEnvironment = @{}
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location -LiteralPath $projectRoot
try {
    & mvn @mavenOptions -pl order-service,inventory-service,gateway-service -am -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Service build failed.' }
    New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null
    $evidencePath = Join-Path $reportRoot 'checks.json'
    if (Test-Path -LiteralPath $evidencePath) { Remove-Item -LiteralPath $evidencePath }
    foreach ($suite in @('SagaWorkflowIntegrationTest','ObservabilityIntegrationTest')) {
        $oldReport = Join-Path $reportRoot "TEST-com.rom.orderflow.integration.$suite.xml"
        if (Test-Path -LiteralPath $oldReport) { Remove-Item -LiteralPath $oldReport }
    }

    $env:PROMETHEUS_PORT = '19090'
    $env:GRAFANA_PORT = '13000'
    $env:ZIPKIN_PORT = '19411'
    $env:ALERTMANAGER_PORT = '19093'
    $env:BLACKBOX_PORT = '19115'
    $env:PROMETHEUS_CONFIG = './monitoring/prometheus/prometheus-integration.yml'
    $env:GRAFANA_PASSWORD = 'orderflow-local'
    & docker compose -f docker-compose.observability.yml -p orderflow-day10 down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw 'Could not reset dedicated monitoring stack.' }
    & docker compose -f docker-compose.observability.yml -p orderflow-day10 up -d
    if ($LASTEXITCODE -ne 0) { throw 'Could not start dedicated monitoring stack.' }
    & docker exec orderflow-day10-prometheus-1 promtool check config /etc/prometheus/prometheus.yml
    if ($LASTEXITCODE -ne 0) { throw 'Prometheus configuration check failed.' }
    & docker exec -w /etc/prometheus orderflow-day10-prometheus-1 promtool test rules alerts-test.yml
    if ($LASTEXITCODE -ne 0) { throw 'Prometheus alert rule tests failed.' }

    foreach ($suite in @('ObservabilityIntegrationTest','SagaWorkflowIntegrationTest')) {
        # Reset only the dedicated Day9/Day10 test project between suites.
        & docker compose -f docker-compose.integration.yml -p orderflow-it down --volumes --remove-orphans
        if ($LASTEXITCODE -ne 0) { throw 'Could not reset test infrastructure.' }
        & docker compose -f docker-compose.integration.yml -p orderflow-it up -d --wait --wait-timeout 120
        if ($LASTEXITCODE -ne 0) { throw 'Test infrastructure did not become healthy.' }
        $tracing = ($suite -eq 'ObservabilityIntegrationTest').ToString().ToLowerInvariant()
        & mvn @mavenOptions -f integration-tests/pom.xml "-Dtest=$suite" "-Dorderflow.it.observability=$tracing" "-Dorderflow.it.reportsDirectory=$reportRoot" test
        if ($LASTEXITCODE -ne 0) { throw "$suite failed; see $reportRoot" }
    }
    @{
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
        prometheusConfig = 'PASS'
        alertRuleTests = 'PASS'
        serviceBuild = 'PASS'
        monitoringProject = 'orderflow-day10'
        businessInfrastructureProject = 'orderflow-it'
    } | ConvertTo-Json | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    & python scripts/summarize-day10.py
    if ($LASTEXITCODE -ne 0) { throw 'Could not generate Day10 summary.' }
}
finally {
    if (-not $KeepInfrastructure) {
        & docker compose -f docker-compose.integration.yml -p orderflow-it down --volumes --remove-orphans
        & docker compose -f docker-compose.observability.yml -p orderflow-day10 down --volumes --remove-orphans
    }
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    Pop-Location
}
