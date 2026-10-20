param(
    [Parameter(Mandatory = $true)][string]$PostgresBin,
    [ValidateRange(1024, 65535)][int]$Port = 55432
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot = Join-Path $repository '.local'
$clusterRoot = Join-Path $localRoot 'postgres'
$dataPath = Join-Path $clusterRoot 'data'
$credentialsPath = Join-Path $clusterRoot 'credentials.json'
$markerPath = Join-Path $clusterRoot 'evcharging-local-cluster.marker'
$binaryRoot = [IO.Path]::GetFullPath($PostgresBin)
foreach ($binaryName in @('initdb.exe', 'pg_ctl.exe', 'createdb.exe', 'pg_isready.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $binaryRoot $binaryName) -PathType Leaf)) {
        throw "PostgreSQL binary missing: $binaryName in $binaryRoot"
    }
}
if (Test-Path -LiteralPath $dataPath) {
    if (-not (Test-Path -LiteralPath $markerPath) -or -not (Test-Path -LiteralPath $credentialsPath)) {
        throw 'Existing data directory has no EV Charging ownership marker/credentials. Review it manually; nothing was changed.'
    }
    $settings = Get-Content -Raw -LiteralPath $credentialsPath | ConvertFrom-Json
    if ($settings.port -ne $Port) { throw "Existing cluster uses port $($settings.port). Supply that port." }
} else {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $Port is already in use. Choose a different port; no existing service will be used."
    }
    New-Item -ItemType Directory -Path $clusterRoot -Force | Out-Null
    # The local credential file is limited to this Windows user. No password is printed.
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $clusterRoot '/inheritance:r' '/grant:r' "${identity}:(OI)(CI)F" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not secure the local database credential directory.' }
    $passwordBytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($passwordBytes) } finally { $random.Dispose() }
    $settings = [ordered]@{
        username = 'evcharging'
        password = [Convert]::ToBase64String($passwordBytes)
        database = 'evcharging'
        port = $Port
        binaryRoot = $binaryRoot
    }
    $settings | ConvertTo-Json | Set-Content -LiteralPath $credentialsPath -Encoding UTF8
    $passwordPath = Join-Path $clusterRoot 'initdb-password.tmp'
    try {
        [IO.File]::WriteAllText($passwordPath, $settings.password)
        & (Join-Path $binaryRoot 'initdb.exe') '-D' $dataPath '-U' $settings.username '--encoding=UTF8' '--locale=C' '--auth=scram-sha-256' "--pwfile=$passwordPath"
        if ($LASTEXITCODE -ne 0) { throw 'initdb failed. Review the output; the partial directory was preserved for inspection.' }
    } finally {
        if (Test-Path -LiteralPath $passwordPath) { Remove-Item -LiteralPath $passwordPath -Force }
    }
    @"
listen_addresses = '127.0.0.1'
port = $Port
password_encryption = 'scram-sha-256'
"@ | Add-Content -LiteralPath (Join-Path $dataPath 'postgresql.conf')
    'EV Charging isolated local database; managed only by ops scripts.' | Set-Content -LiteralPath $markerPath
}
& (Join-Path $binaryRoot 'pg_ctl.exe') '-D' $dataPath 'status' *> $null
if ($LASTEXITCODE -ne 0) {
    # pg_ctl starts a background server and returns after startup. No visible helper window.
    $startup = Start-Process -FilePath (Join-Path $binaryRoot 'pg_ctl.exe') -ArgumentList @('-D', ('"' + $dataPath + '"'), '-l', ('"' + (Join-Path $clusterRoot 'postgres.log') + '"'), '-w', 'start') -WindowStyle Hidden -PassThru
    # WaitForExit observes pg_ctl only; Start-Process -Wait would also wait for its server child.
    $startup.WaitForExit()
    if ($startup.ExitCode -ne 0) { throw "PostgreSQL startup failed. Inspect $clusterRoot\postgres.log" }
}
$oldPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $settings.password
    # createdb reports an existing database as a benign error; psql is deliberately not used.
    & (Join-Path $binaryRoot 'createdb.exe') '-h' '127.0.0.1' '-p' "$Port" '-U' $settings.username $settings.database 2> (Join-Path $clusterRoot 'createdb.log')
    if ($LASTEXITCODE -ne 0) {
        $createMessage = Get-Content -Raw -LiteralPath (Join-Path $clusterRoot 'createdb.log')
        if ($createMessage -notmatch 'already exists') { throw "Could not create the isolated database. Inspect $clusterRoot\createdb.log" }
    }
} finally { $env:PGPASSWORD = $oldPassword }
Write-Host "Isolated PostgreSQL is ready on 127.0.0.1:$Port. Credentials are stored privately under .local/postgres."
