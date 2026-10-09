param(
    [string]$MavenRepository,
    [switch]$KeepInfrastructure
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
$mavenOptions = @()
if ($MavenRepository) {
    $mavenOptions += "-Dmaven.repo.local=$MavenRepository"
}
try {
    & mvn @mavenOptions -pl order-service,inventory-service -am -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Service build failed.' }

    # This project and its databases are reserved exclusively for disposable tests.
    & docker compose -f docker-compose.integration.yml -p orderflow-it down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw 'Could not reset test infrastructure.' }
    & docker compose -f docker-compose.integration.yml -p orderflow-it up -d --wait --wait-timeout 120
    if ($LASTEXITCODE -ne 0) { throw 'Test infrastructure did not become healthy.' }

    $suiteReport = Join-Path $projectRoot 'integration-tests/target/surefire-reports/TEST-com.rom.orderflow.integration.SagaWorkflowIntegrationTest.xml'
    if (Test-Path -LiteralPath $suiteReport) {
        Remove-Item -LiteralPath $suiteReport
    }
    & mvn @mavenOptions -f integration-tests/pom.xml test
    $testExitCode = $LASTEXITCODE
    if ((Test-Path -LiteralPath $suiteReport) -and (Get-Command python -ErrorAction SilentlyContinue)) {
        & python scripts/summarize-integration-tests.py
        if ($LASTEXITCODE -ne 0) { throw 'Could not generate the integration test summary.' }
    }
    if ($testExitCode -ne 0) { throw 'Integration tests failed; see integration-tests/target/surefire-reports.' }
}
finally {
    if (-not $KeepInfrastructure) {
        & docker compose -f docker-compose.integration.yml -p orderflow-it down --volumes --remove-orphans
    }
    Pop-Location
}
