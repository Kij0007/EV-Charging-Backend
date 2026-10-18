$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$clusterRoot = Join-Path $repository '.local/postgres'
$dataPath = [IO.Path]::GetFullPath((Join-Path $clusterRoot 'data'))
if (-not $dataPath.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing an unexpected database path.'
}
if (-not (Test-Path -LiteralPath (Join-Path $clusterRoot 'evcharging-local-cluster.marker'))) {
    throw 'No owned local cluster found. No server was stopped.'
}
$settings = Get-Content -Raw -LiteralPath (Join-Path $clusterRoot 'credentials.json') | ConvertFrom-Json
& (Join-Path $settings.binaryRoot 'pg_ctl.exe') '-D' $dataPath '-m' 'fast' '-w' 'stop'
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the owned local cluster; inspect its log.' }
Write-Host 'Local PostgreSQL stopped. Its durable data was preserved.'
