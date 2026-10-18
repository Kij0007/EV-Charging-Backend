param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$AdminEmail = $env:APP_ADMIN_EMAIL,
    [ValidateRange(1024, 65535)][int]$Port = 8081,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot = Join-Path $repository '.local'
$credentialsPath = Join-Path $localRoot 'postgres/credentials.json'
if (-not (Test-Path -LiteralPath $credentialsPath)) {
    throw 'Run ops/Start-LocalDatabase.ps1 first, or use the documented existing PostgreSQL environment setup instead.'
}
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe'))) {
    throw 'Supply -JdkHome with your JDK 17 installation directory (or set JAVA_HOME).'
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port is already in use. No existing process was stopped."
}
$env:JAVA_HOME = [IO.Path]::GetFullPath($JdkHome)
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:Path
Push-Location -LiteralPath $repository
try {
    if (-not $SkipBuild) {
        & (Join-Path $repository 'mvnw.cmd') '--batch-mode' '--no-transfer-progress' 'clean' 'package'
        if ($LASTEXITCODE -ne 0) { throw 'Build/tests failed. The website was not started.' }
    }
} finally { Pop-Location }
$jars = @(Get-ChildItem -LiteralPath (Join-Path $repository 'target') -Filter '*.jar' -File | Where-Object Name -NotLike '*.original')
if ($jars.Count -ne 1) { throw 'Expected exactly one executable jar in target. Run the Maven build.' }
$settings = Get-Content -Raw -LiteralPath $credentialsPath | ConvertFrom-Json
$savedEnvironment = @{}
foreach ($key in @('SPRING_PROFILES_ACTIVE', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'SERVER_ADDRESS', 'SERVER_PORT', 'APP_ADMIN_EMAIL', 'APP_ADMIN_PASSWORD')) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:SERVER_PORT = "$Port"
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:$($settings.port)/$($settings.database)"
    $env:DB_USERNAME = $settings.username
    $env:DB_PASSWORD = $settings.password
    if (-not $AdminEmail) { $AdminEmail = Read-Host 'Administrator email (first startup only; leave blank to skip creation)' }
    if ($AdminEmail) {
        $env:APP_ADMIN_EMAIL = $AdminEmail
        if (-not $env:APP_ADMIN_PASSWORD) {
            $securePassword = Read-Host 'Administrator password (at least 12 characters; existing passwords are never reset)' -AsSecureString
            $credential = New-Object System.Management.Automation.PSCredential('bootstrap', $securePassword)
            $env:APP_ADMIN_PASSWORD = $credential.GetNetworkCredential().Password
        }
    } else {
        $env:APP_ADMIN_EMAIL = ''
        $env:APP_ADMIN_PASSWORD = ''
    }
    New-Item -ItemType Directory -Path $localRoot -Force | Out-Null
    $process = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList @('-jar', ('"' + $jars[0].FullName + '"')) -WorkingDirectory $repository -WindowStyle Hidden -RedirectStandardOutput (Join-Path $localRoot 'website.log') -RedirectStandardError (Join-Path $localRoot 'website-error.log') -PassThru
    [ordered]@{ pid = $process.Id; startedAt = $process.StartTime.ToUniversalTime().ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $localRoot 'website-process.json')
} finally {
    foreach ($key in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process')
    }
}
$deadline = [DateTime]::UtcNow.AddSeconds(90)
while ([DateTime]::UtcNow -lt $deadline) {
    $process.Refresh()
    if ($process.HasExited) { throw 'Website exited during startup. Inspect .local/website.log and .local/website-error.log.' }
    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/actuator/health" -UseBasicParsing -TimeoutSec 2
        if ($response.StatusCode -eq 200) {
            Write-Host "Website ready: http://127.0.0.1:$Port/"
            Write-Host 'Use ops/Stop-Website.ps1 to stop it. Database contents are durable.'
            exit 0
        }
    } catch { }
    Start-Sleep -Milliseconds 500
}
throw 'Startup health check timed out. Inspect .local/website.log and .local/website-error.log.'
