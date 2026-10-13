param([string]$MavenRepository)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$mavenOptions = @()
if ($MavenRepository) { $mavenOptions += "-Dmaven.repo.local=$MavenRepository" }
$reportRoot = Join-Path $projectRoot 'integration-tests/target/day11-reports'
Push-Location -LiteralPath $projectRoot
try {
    New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null
    foreach ($file in @('checks.json', 'TEST-com.rom.orderflow.integration.RedisCacheIntegrationTest.xml')) {
        $oldReport = Join-Path $reportRoot $file
        if (Test-Path -LiteralPath $oldReport) { Remove-Item -LiteralPath $oldReport }
    }
    & mvn -B -ntp @mavenOptions -pl order-service,inventory-service,gateway-service -am -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Service build failed.' }
    & docker compose -f docker-compose.day11.yml -p orderflow-day11 up -d --wait --wait-timeout 60
    if ($LASTEXITCODE -ne 0) { throw 'Day11 Redis did not become healthy.' }
    & mvn -B -ntp @mavenOptions -pl order-service '-Dtest=OrderReadCacheTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Order cache tests failed.' }
    & mvn -B -ntp @mavenOptions -pl inventory-service '-Dtest=InventoryReadCacheTest,InventoryRedisIntegrationTest,InventoryCacheInvalidationTest' '-Dorderflow.day11.redis=true' test
    if ($LASTEXITCODE -ne 0) { throw 'Day11 cache tests failed; see inventory-service/target/surefire-reports.' }
    foreach ($service in @('order', 'inventory')) {
        Get-ChildItem -Path "$service-service/target/surefire-reports/TEST-*Cache*.xml" | Copy-Item -Destination $reportRoot
    }
    Copy-Item -LiteralPath 'inventory-service/target/surefire-reports/TEST-com.rom.orderflow.inventory.cache.InventoryRedisIntegrationTest.xml' -Destination $reportRoot
    # Reserved disposable databases only; development project orderflow is never reset.
    & docker compose -f docker-compose.integration.yml -p orderflow-it down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw 'Could not reset dedicated test infrastructure.' }
    & docker compose -f docker-compose.integration.yml -p orderflow-it up -d --wait --wait-timeout 120
    if ($LASTEXITCODE -ne 0) { throw 'Test PostgreSQL/Kafka did not become healthy.' }
    & mvn -B -ntp @mavenOptions -f integration-tests/pom.xml '-Dtest=RedisCacheIntegrationTest' '-Dorderflow.it.redis=true' "-Dorderflow.it.reportsDirectory=$reportRoot" test
    if ($LASTEXITCODE -ne 0) { throw "HTTP/Kafka/Redis tests failed; see $reportRoot" }
    @{
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
        serviceBuild = 'PASS'
        orderTtlSeconds = 45
        redisProject = 'orderflow-day11'
        businessProject = 'orderflow-it'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reportRoot 'checks.json') -Encoding UTF8
}
finally {
    & docker compose -f docker-compose.integration.yml -p orderflow-it down --volumes --remove-orphans
    & docker compose -f docker-compose.day11.yml -p orderflow-day11 down
    Pop-Location
}
