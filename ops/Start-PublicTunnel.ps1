param(
    [string]$NgrokPath = '',
    [ValidateRange(1024,65535)][int]$Port = 18082,
    [string]$PublicDomain = '',
    [switch]$PreflightOnly
)

function Test-SecureSessionCookie {
    param([AllowNull()][AllowEmptyCollection()][string[]]$SetCookieHeaders)
    $foundSession = $false
    foreach ($header in $SetCookieHeaders) {
        if ([string]::IsNullOrWhiteSpace($header)) { continue }
        if ($header -match '[\r\n]') { return $false }
        # Windows PowerShell can coalesce Set-Cookie values. Split only before
        # another cookie name=value, never at the comma inside an Expires date.
        $cookies = [regex]::Split($header, ',(?=\s*[A-Za-z0-9!#$%&''*+.^_`|~-]+=)')
        foreach ($cookie in $cookies) {
            $parts = $cookie.Split([char]';')
            $pair = $parts[0].Trim()
            if ($pair -cnotmatch '^JSESSIONID=') { continue }
            $foundSession = $true
            if ($pair -cnotmatch '^JSESSIONID=[^\s,;]+$') { return $false }
            $secure = $false
            $httpOnly = $false
            $sameSiteCount = 0
            $sameSiteLax = $false
            for ($index = 1; $index -lt $parts.Length; $index++) {
                $attribute = $parts[$index].Trim()
                if ($attribute -ieq 'Secure') { $secure = $true }
                elseif ($attribute -ieq 'HttpOnly') { $httpOnly = $true }
                elseif ($attribute -match '^SameSite\s*=') {
                    $sameSiteCount++
                    $sameSiteLax = $attribute -match '^SameSite\s*=\s*Lax$'
                }
            }
            # Reject ambiguity or an insecure duplicate session cookie.
            if (-not $secure -or -not $httpOnly -or $sameSiteCount -ne 1 -or -not $sameSiteLax) {
                return $false
            }
        }
    }
    return $foundSession
}

function ConvertTo-HealthJsonText {
    param([AllowNull()][object]$Content)
    # Invoke-WebRequest can expose non-HTML/vendor-media content as byte[].
    # Decode it once rather than piping individual byte values to JSON parsing.
    if ($Content -is [string]) {
        $text = $Content
    } elseif ($Content -is [byte[]]) {
        $utf8 = New-Object Text.UTF8Encoding($false, $true)
        $text = $utf8.GetString($Content)
    } else {
        throw 'Unsupported health response content representation.'
    }
    return $text.TrimStart([char]0xFEFF)
}

$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

# Only the HTTP application is shared. Never create a raw TCP/database tunnel.
if ($Port -in @(5432, 55432, 55433)) {
    throw 'Select the Spring Boot HTTP port, not a PostgreSQL port.'
}
if ($PublicDomain) {
    $PublicDomain = $PublicDomain.Trim().ToLowerInvariant()
    if ($PublicDomain -notmatch '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.ngrok-free\.(app|dev)$') {
        throw 'PublicDomain must be the assigned free ngrok hostname only, without https://, port or path. Leave it blank to use account assignment.'
    }
}
if (-not $NgrokPath) {
    $installed = Get-Command ngrok.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($installed) {
        $NgrokPath = $installed.Source
    } else {
        $NgrokPath = Join-Path $repository '.local/tools/ngrok.exe'
    }
}
if (-not (Test-Path -LiteralPath $NgrokPath -PathType Leaf)) {
    throw 'ngrok.exe was not found. Install the official Windows agent or supply -NgrokPath. This helper does not download or configure credentials.'
}
$NgrokPath = (Resolve-Path -LiteralPath $NgrokPath).ProviderPath
if ([IO.Path]::GetExtension($NgrokPath) -ine '.exe') {
    throw 'Supply the official ngrok Windows executable.'
}

$origin = "http://127.0.0.1:$Port"
$healthStage = 'request'
$healthResponseCode = 'unavailable'
$health = $null
try {
    $health = Invoke-WebRequest -Uri "$origin/actuator/health" -UseBasicParsing -TimeoutSec 5 -MaximumRedirection 0
    $healthResponseCode = [string][int]$health.StatusCode
    $healthStage = 'decode'
    $healthText = ConvertTo-HealthJsonText -Content $health.Content
    $healthStage = 'parse'
    $healthBody = ConvertFrom-Json -InputObject $healthText
    $healthStage = 'status'
    if ($health.StatusCode -ne 200 -or $healthBody.status -cne 'UP') {
        throw 'Local application is not healthy.'
    }
} catch {
    $healthFailureType = $_.Exception.GetType().FullName
    if ($healthResponseCode -eq 'unavailable') {
        # PS5 and PS7 use different response classes. Only a numeric HTTP code
        # is exposed; never print an exception message, URI, headers or body.
        try {
            if ($null -ne $_.Exception.Response -and $null -ne $_.Exception.Response.StatusCode) {
                $healthResponseCode = [string][int]$_.Exception.Response.StatusCode
            }
        } catch { }
    }
    throw "Local health preflight failed (stage=$healthStage; type=$healthFailureType; HTTP=$healthResponseCode). Start the reviewed app/database with profiles local,tunnel and the selected HTTP port. No tunnel was started."
} finally {
    $health = $null
    $healthBody = $null
    $healthText = $null
}

try {
    # This creates one anonymous CSRF session, with no account/password supplied.
    # Its response body/token and cookie values must never be printed or logged.
    $csrf = Invoke-WebRequest -Uri "$origin/api/auth/csrf" -UseBasicParsing -TimeoutSec 5 -MaximumRedirection 0
    $sessionCookieHeaders = @()
    if ($csrf.Headers -is [System.Net.WebHeaderCollection]) {
        $sessionCookieHeaders = @($csrf.Headers.GetValues('Set-Cookie'))
    } else {
        foreach ($headerName in $csrf.Headers.Keys) {
            if ([string]$headerName -ieq 'Set-Cookie') {
                $sessionCookieHeaders = @($csrf.Headers[$headerName])
                break
            }
        }
    }
    if ($csrf.StatusCode -ne 200 -or -not (Test-SecureSessionCookie -SetCookieHeaders $sessionCookieHeaders)) {
        throw 'Required session cookie attributes were absent.'
    }
} catch {
    throw 'Security preflight failed: the local app did not issue a JSESSIONID cookie with Secure, HttpOnly and SameSite=Lax. Start with profiles local,tunnel; do not share the ordinary local-only profile. No tunnel was started.'
} finally {
    $csrf = $null
    $sessionCookieHeaders = $null
}
Write-Host 'PASS: local health and Secure/HttpOnly/SameSite=Lax session-cookie preflight.'
if ($PreflightOnly) {
    Write-Host 'Preflight only: ngrok was not started and no credentials were requested.'
    return
}

$ngrokArguments = @('http', $origin, '--inspect=false')
if ($PublicDomain) {
    # Choose the account-assigned free hostname explicitly. Do not request a
    # paid custom/random domain, or use the deprecated --scheme flag.
    $ngrokArguments += @('--url', "https://$PublicDomain")
}
Write-Host "Local health is UP: $origin"
Write-Host 'Starting the foreground ngrok HTTPS endpoint with agent HTTP inspection disabled.'
Write-Host 'Use the HTTPS Forwarding URL printed by ngrok. The default is your account-assigned dev domain.'
Write-Host 'Verify HTTP redirects/is unavailable before entering credentials; never log in over HTTP.'
Write-Host 'Keep this terminal, Spring Boot, PostgreSQL, your PC and its internet connection running.'
Write-Host 'Press Ctrl+C here to stop public access. Database records are not deleted.'

# The owner configures the account privately. This helper does not read, print,
# accept, save or put an authtoken on the command line. No requests are replayed.
# No Windows service, firewall rule, router forwarding or administrator action.
& $NgrokPath @ngrokArguments
if ($LASTEXITCODE -ne 0) {
    throw "ngrok exited with code $LASTEXITCODE. Review the agent's account/authentication/usage message locally; do not paste tokens into chat."
}
