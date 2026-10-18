$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$metadataPath = Join-Path $repository '.local/website-process.json'
if (-not (Test-Path -LiteralPath $metadataPath)) { throw 'No website process record found; no process was stopped.' }
$metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
$websiteProcess = Get-Process -Id $metadata.pid -ErrorAction SilentlyContinue
if (-not $websiteProcess) { Write-Host 'Website is already stopped.'; exit 0 }
$expectedStart = [DateTime]::Parse($metadata.startedAt).ToUniversalTime()
if ([Math]::Abs(($websiteProcess.StartTime.ToUniversalTime() - $expectedStart).TotalSeconds) -gt 1 -or $websiteProcess.ProcessName -ne 'java') {
    throw 'The saved PID belongs to a different process. No process was stopped.'
}
Stop-Process -Id $websiteProcess.Id
Write-Host 'Website stopped. Local database data was preserved.'
