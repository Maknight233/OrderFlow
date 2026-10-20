param(
    [string]$RegistryUser = 'Maknight233',
    [switch]$RegistryOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Read-PrivateValue([string]$Prompt) {
    $secureValue = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        $plainValue = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        if ([string]::IsNullOrEmpty($plainValue)) { throw 'The secret must not be empty.' }
        return $plainValue
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secureValue.Dispose()
    }
}

function Encode-PrivateValue([string]$Value) {
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
}

# Use the caller's current kubectl context; create the namespace separately.
& kubectl get namespace orderflow -o name
if ($LASTEXITCODE -ne 0) { throw 'Create the orderflow namespace in the intended context first.' }
if ([string]::IsNullOrWhiteSpace($RegistryUser)) { throw 'RegistryUser must not be empty.' }

$secrets = @()
$databasePassword = $null
$registryToken = $null
$registryConfig = $null
$payload = $null
try {
    if (-not $RegistryOnly) {
        $existing = & kubectl -n orderflow get secret postgres-credentials -o name --ignore-not-found
        if ($LASTEXITCODE -ne 0) { throw 'Cannot check the database Secret.' }
        if ($existing) {
            throw 'postgres-credentials already exists. Use -RegistryOnly for token renewal. Rotate the database password with ALTER ROLE and update the Secret as a separate operation.'
        }
        $databasePassword = Read-PrivateValue 'New PostgreSQL password (stored only in the cluster)'
        $secrets += @{
            apiVersion = 'v1'; kind = 'Secret'
            metadata = @{ name = 'postgres-credentials'; namespace = 'orderflow' }
            type = 'Opaque'
            data = @{ POSTGRES_PASSWORD = (Encode-PrivateValue $databasePassword) }
        }
    }

    $registryToken = Read-PrivateValue 'GHCR pull token (classic PAT with read:packages)'
    $registryConfig = @{
        auths = @{
            'ghcr.io' = @{ auth = (Encode-PrivateValue "${RegistryUser}:$registryToken") }
        }
    } | ConvertTo-Json -Depth 5 -Compress
    $secrets += @{
        apiVersion = 'v1'; kind = 'Secret'
        metadata = @{ name = 'ghcr-auth'; namespace = 'orderflow' }
        type = 'kubernetes.io/dockerconfigjson'
        data = @{ '.dockerconfigjson' = (Encode-PrivateValue $registryConfig) }
    }
    $payload = @{ apiVersion = 'v1'; kind = 'List'; items = $secrets } | ConvertTo-Json -Depth 10 -Compress

    # No credentials in process arguments, local files, or last-applied annotations.
    $payload | & kubectl apply --server-side --field-manager=orderflow-secret-setup -f -
    if ($LASTEXITCODE -ne 0) { throw 'Secret apply failed. Check context, RBAC and field-manager conflicts.' }
}
finally {
    $databasePassword = $null
    $registryToken = $null
    $registryConfig = $null
    $payload = $null
    $secrets = $null
}
