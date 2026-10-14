param([string]$MavenRepository, [switch]$SkipHttpSuite, [switch]$RunRegressions)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$reportRoot = Join-Path $projectRoot 'integration-tests/target/day12-reports'
$mavenOptions = @()
if ($MavenRepository) { $mavenOptions += "-Dmaven.repo.local=$MavenRepository" }
Push-Location -LiteralPath $projectRoot
try {
    New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null
    $checks = Join-Path $reportRoot 'checks.json'
    if (Test-Path -LiteralPath $checks) { Remove-Item -LiteralPath $checks }
    & mvn -B -ntp @mavenOptions -pl order-service,inventory-service,gateway-service -am -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Day12 service build failed.' }
    & mvn -B -ntp @mavenOptions -pl gateway-service '-Dtest=GatewayConcurrencyFilterTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Gateway concurrency unit tests failed.' }
    Copy-Item -LiteralPath 'gateway-service/target/surefire-reports/TEST-com.rom.orderflow.gateway.resilience.GatewayConcurrencyFilterTest.xml' -Destination $reportRoot
    if (-not $SkipHttpSuite) {
        & mvn -B -ntp @mavenOptions -f integration-tests/pom.xml '-Dtest=GatewayResilienceIntegrationTest' "-Dorderflow.it.reportsDirectory=$reportRoot" test
        if ($LASTEXITCODE -ne 0) { throw 'Gateway resilience HTTP suite failed.' }
    }
    & docker compose -f docker-compose.integration.yml -p orderflow-it down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw 'Could not reset dedicated test infrastructure.' }
    & docker compose -f docker-compose.integration.yml -p orderflow-it up -d --wait --wait-timeout 120
    if ($LASTEXITCODE -ne 0) { throw 'Test PostgreSQL/Kafka are not healthy.' }
    & docker compose -f docker-compose.day11.yml -p orderflow-day11 up -d --wait --wait-timeout 60
    if ($LASTEXITCODE -ne 0) { throw 'Test Redis is not healthy.' }
    & mvn -B -ntp @mavenOptions -f integration-tests/pom.xml '-Dtest=ResilienceSagaIntegrationTest' '-Dorderflow.it.redis=true' "-Dorderflow.it.reportsDirectory=$reportRoot" test
    if ($LASTEXITCODE -ne 0) { throw 'Day12 real Saga suite failed.' }
    @{
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
        serviceBuild = 'PASS'
        rateLimiting = 'OPTIONAL_DEFERRED'
    } | ConvertTo-Json | Set-Content -LiteralPath $checks -Encoding UTF8
}
finally {
    & docker compose -f docker-compose.integration.yml -p orderflow-it down --volumes --remove-orphans
    & docker compose -f docker-compose.day11.yml -p orderflow-day11 down
    Pop-Location
}
if ($RunRegressions) {
    & (Join-Path $PSScriptRoot 'run-day11-tests.ps1') -MavenRepository $MavenRepository
    & (Join-Path $PSScriptRoot 'run-day10-tests.ps1') -MavenRepository $MavenRepository -RefreshDay9Summary
    & python (Join-Path $PSScriptRoot 'summarize-day11.py')
    if ($LASTEXITCODE -ne 0) { throw 'Could not summarize Day11 regression.' }
    & python (Join-Path $PSScriptRoot 'summarize-day12.py')
    if ($LASTEXITCODE -ne 0) { throw 'Could not summarize Day12.' }
}
