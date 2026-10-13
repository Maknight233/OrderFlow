param([string]$MavenRepository)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$mavenOptions = @()
if ($MavenRepository) { $mavenOptions += "-Dmaven.repo.local=$MavenRepository" }
Push-Location -LiteralPath $projectRoot
try {
    & docker compose -f docker-compose.day11.yml -p orderflow-day11 up -d --wait --wait-timeout 60
    if ($LASTEXITCODE -ne 0) { throw 'Day11 Redis did not become healthy.' }
    & mvn -B -ntp @mavenOptions -pl inventory-service '-Dtest=InventoryReadCacheTest,InventoryRedisIntegrationTest,InventoryCacheInvalidationTest' '-Dorderflow.day11.redis=true' test
    if ($LASTEXITCODE -ne 0) { throw 'Day11 cache tests failed; see inventory-service/target/surefire-reports.' }
}
finally {
    & docker compose -f docker-compose.day11.yml -p orderflow-day11 down
    Pop-Location
}
