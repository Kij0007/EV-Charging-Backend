# Run the website on your PC with one public link

Current choice: **Spring Boot and PostgreSQL stay on your Windows PC. ngrok forwards a public HTTPS address to the local application while it is running.** Render hosting is no longer the selected setup. This guide does not delete or alter any old cloud resource.

## 1. What runs where

```text
Visitor -> assigned ngrok HTTPS address -> encrypted ngrok tunnel
        -> ngrok agent on your PC -> Spring Boot on 127.0.0.1:18082
        -> your selected local PostgreSQL database
```

You share the HTTPS website address, never a database port, database password, PC IP address or ngrok authtoken. The UI and API remain together in Spring Boot, so relative `/api/...` calls use the public hostname automatically. There is no separate frontend deployment or permissive CORS setting to add.

The link works only while all of these are available: your PC is awake, internet is connected, PostgreSQL is running, Spring Boot is healthy and the ngrok agent is running. Closing the tunnel stops public access. Restarting the tunnel can reuse the assigned domain; restarting Java can require users to sign in again. Neither action deletes database records.

### Prepared launcher for this computer

The deliverables include [Start-PC-Public-Website.ps1](../../Start-PC-Public-Website.ps1), beside this repository in `outputs/`. It starts the already prepared workspace database and packaged application, then asks **you** for the ngrok authtoken using hidden local input. From the workspace directory, run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\outputs\Start-PC-Public-Website.ps1
```

`-ExecutionPolicy Bypass` applies only to that PowerShell process; the launcher does not change the machine's execution policy. Keep its window open. Use the HTTPS forwarding address printed by ngrok. The token is supplied through the child process environment, is not saved by the launcher and is never a command-line argument. Enter it only in the owner's local prompt, not in chat.

This launcher is specifically for the prepared files on **this PC**: Java 17, ngrok 3.39.11, the packaged JAR and the existing PostgreSQL 17.11 cluster under `work/postgres-root/data`, port **55433**, database **evcharging**. It checks the server's real data directory, version, role and database before starting Java. It never creates a database, adopts the older `EVCHARGING` database on 5432, resets administrator credentials or installs a service. The separate backup/migration work for that older database remains pending.

The app runs hidden on loopback port 18082 with `local,tunnel`; its logs and an owned-process record go under `work/pc-public-runtime/`. An occupied app port causes a clear failure without stopping or sharing the existing process. Stop your own conflicting IDE run or deliberately pass another `-Port`. The launcher clears conflicting inherited Spring/database options for its children and restores the calling process environment when it exits. Passwords are passed privately through environment values, while datasource/Flyway targets remain pinned to the reviewed cluster.

Press **Ctrl+C** in the launcher window to stop the tunnel and its own Java process. It checks that Java's PID, start time and executable still match before stopping it. PostgreSQL and its records are deliberately left intact. Force-closing a terminal, killing PowerShell or losing power can interrupt cleanup; the local process record identifies the application that this launcher created. Do not stop unrelated processes.

The script can run in an ordinary PowerShell window after closing Codex. Keep the prepared workspace files in place; this is not a fresh-install package and does not work by moving just the script or the source ZIP to another computer. `-ApplicationOnly` is provided for operator verification: it starts/checks the app without asking for an ngrok token or starting a tunnel. The launcher has conservative JVM/thread/pool limits, which are not a measured public-traffic capacity guarantee. Runtime verification results belong in the change report; merely having this script is not a successful deployment.

## 2. Understand the Free link

Current official ngrok documentation provides one automatically assigned development domain per Free account, such as `your-assigned-name.ngrok-free.app` or `.ngrok-free.dev`. A purchased domain is unnecessary. `ngrok http <port>` automatically chooses the assigned domain; use the exact HTTPS URL that your own agent displays. The hostname belongs to the account and is reusable across agent restarts, subject to the account remaining active and retaining its domain. [Official dev-domain explanation](https://ngrok.com/blog/free-static-domains-ngrok-users)

At the time this guide was prepared, Free limits included **1 GB outbound transfer and 20,000 HTTP requests per month**, a 4,000-per-minute request limit, and up to three endpoints/agents. Free HTML visits show an ngrok warning page; visitors click **Visit Site** to continue, then its cookie suppresses the warning for that domain for seven days. This warning is the provider's page, not a broken application button. Free endpoints have no fixed session timeout, but usage/account/network limits still apply. Do not select a paid plan or add billing to remove these limits. [Official Free-plan limits](https://ngrok.com/docs/pricing-limits/free-plan-limits)

The helper disables the agent's HTTP inspection with `--inspect=false`; the default inspector can otherwise retain request bodies, headers and responses, including login traffic. HTTPS terminates at ngrok's edge and reaches the local agent through its secured tunnel. Disabling inspection does not mean the provider is absent from the request path. [Official CLI flags](https://ngrok.com/docs/gateway/agent/cli), [inspection behavior](https://ngrok.com/docs/gateway/agent/web-inspection-interface)

## 3. Start the correct local application first

Use Java 17 and the reviewed project containing `application-tunnel.properties`. In your IDE's Spring Boot run configuration, set:

| Setting | Value / source |
|---|---|
| Active profiles | `local,tunnel` |
| `SERVER_PORT` | `18082` (or another deliberately selected unused application port) |
| `SERVER_ADDRESS` | `127.0.0.1`; the tunnel profile enforces loopback binding |
| `DB_URL` | Your **reviewed local PostgreSQL database** JDBC URL |
| `DB_USERNAME`, `DB_PASSWORD` | Private settings for that database; never commit or print the password |
| `APP_DEMO_MODE` | `false` for ordinary PC operation; the old Render database-expiry notice does not describe your local database |
| Administrator bootstrap variables | Only for deliberately creating the first administrator on a fresh database; existing passwords/roles are not reset |

The local database helper in [ops/DATABASE.md](../ops/DATABASE.md) normally creates its dedicated database on 55432. Other verified local setups may use another port, such as 55433. Your application port 18082 and PostgreSQL port are different settings. Match the actual database you selected; do not guess or silently switch to an unrelated database on 5432.

**Existing `EVCHARGING` data still requires its earlier preservation/migration process.** A nonempty schema without Flyway history must not be blindly baselined. Use an already reviewed working local database, or complete the backup/audit/restored-clone rehearsal before pointing the updated application at older data. Starting ngrok cannot repair a database connection or schema error.

Spring Boot does not automatically read a `.env` file or the scripts' private JSON credential file. A separately started IDE also does not inherit variables set later in another terminal. Supply the variables in that IDE run configuration or a properly configured parent launch process. Never copy cloud DB settings into this local run.

Wait for successful application startup. This local read-only check should return `{"status":"UP"}`:

```powershell
Invoke-RestMethod 'http://127.0.0.1:18082/actuator/health'
```

Do not stop an unrelated process if the port is occupied. Choose a different application port and pass the same value to the tunnel helper.

## 4. Install ngrok and let the owner configure the account

The official Windows page recommends its Microsoft Store package:

```powershell
winget install ngrok -s msstore
```

Alternatively, use the standalone executable offered on the [official Windows download page](https://ngrok.com/download/windows). The helper accepts `-NgrokPath` if it is not on PATH. It also checks the repository's ignored `.local/tools/ngrok.exe` as a fallback. No binary or credential is added to Git.

Create/sign in to your own Free ngrok account. The agent needs an account authtoken. **The owner must supply it privately on their own computer. Do not send it in chat, screenshots, source files or a shared log.** The helper never reads the token/configuration file or installs a Windows service.

If you have already configured ngrok privately, proceed to the next section. Otherwise, this local PowerShell pattern supplies the token to the agent through its supported process environment, without putting its value in a command line or saving it in the project:

```powershell
$previousToken = [Environment]::GetEnvironmentVariable('NGROK_AUTHTOKEN', 'Process')
$secret = Read-Host 'Paste your ngrok authtoken privately' -AsSecureString
$credential = New-Object System.Management.Automation.PSCredential('ngrok', $secret)
try {
    $env:NGROK_AUTHTOKEN = $credential.GetNetworkCredential().Password
    .\ops\Start-PublicTunnel.ps1 -Port 18082
} finally {
    [Environment]::SetEnvironmentVariable('NGROK_AUTHTOKEN', $previousToken, 'Process')
    $credential = $null
    $secret = $null
}
```

This call stays in the foreground until you stop it; the temporary environment is restored afterwards. Agent environment-token support is documented in [ngrok's official configuration reference](https://ngrok.com/docs/gateway/agent/config#environment-variables). An existing private agent configuration is an alternative; this project does not open or print that file.

## 5. Open and share the assigned HTTPS link

From the repository, after the app/database and the owner's account setup are ready:

```powershell
.\ops\Start-PublicTunnel.ps1 -Port 18082
# Or use your own explicit executable path:
.\ops\Start-PublicTunnel.ps1 -NgrokPath 'C:\path\to\ngrok.exe' -Port 18082
```

Choose **one** command. The script checks local health and then requests `/api/auth/csrf` without credentials. It requires a `JSESSIONID` response cookie containing **Secure, HttpOnly and SameSite=Lax** before it will start ngrok. This prevents accidentally sharing the ordinary local HTTP profile. Each preflight creates an anonymous CSRF session; the response token and cookie values are not printed or logged. `-PreflightOnly` performs these checks without starting ngrok or requesting credentials.

After the preflight, it starts a foreground HTTP tunnel to 127.0.0.1, with inspection disabled. ngrok prints the account's assigned HTTPS forwarding address. Copy that public address and open it on your PC and a second device. An optional `-PublicDomain 'your-assigned-name.ngrok-free.app'` explicitly selects your already assigned Free hostname over HTTPS; it does not register a new/custom domain. The preflight checks local response flags, not public TLS, forwarding behavior or business functionality.

Verify the public endpoint before entering a password: HTTPS must have a valid certificate, and the corresponding plain HTTP address must redirect to HTTPS or be unavailable rather than serving the login form. No HTTP-redirect success is claimed merely because the start command ran. If plain HTTP serves the form, stop and correct the edge configuration before using credentials. Do not disable Secure cookies to work around an HTTP login issue.

The ngrok interstitial's bypass header is a **client request** feature. Adding a response header to Spring cannot remove a visitor's initial warning page. This guide keeps that warning and introduces no paid bypass or extra proxy.

## 6. Why the tunnel profile matters

`local,tunnel` keeps the application bound to loopback, enables Tomcat's native forwarding only for the local agent at 127.0.0.1/::1, and forces Secure, HttpOnly, SameSite=Lax session cookies. Local-only development keeps its normal profile; public mode is intended for the HTTPS address, including when you use the site from the host PC.

The agent is the trusted hop. Public callers cannot connect directly to a loopback-only app. Do not widen the binding to 0.0.0.0, trust every forwarding sender, open a database port or add a router port-forwarding rule for this setup.

Official ngrok documentation says it appends trusted values to existing `X-Forwarded-For` and `X-Forwarded-Proto`. The native proxy configuration walks client addresses from the right and stops at an untrusted address; it does not trust an attacker's leftmost value. Mixed protocol values are treated conservatively, and the Secure-cookie flag remains enforced independently. Live verification still needs to check the actual agent behavior. [Official upstream-header behavior](https://ngrok.com/docs/gateway/endpoints/http#upstream-headers)

## 7. Verify working behavior, then stop safely

Use the **HTTPS public link** and test one ordinary customer and your administrator with deliberately chosen test records. Confirm registration/login/refresh, station discovery, reserve/cancel, administrator completion, returned capacity, account changes, notification-read state and administrator restrictions. The app still reserves capacity immediately, collects no online charges and activates no physical charger.

An actual local/network/tunnel success must be recorded in the report before calling these checks verified. A script syntax pass does not prove a reachable website. Two browsers on the same network do not prove limits distinguish two different public IP addresses.

To stop sharing, press **Ctrl+C** in the tunnel terminal first. Then stop your own Spring Boot run in the IDE, and stop the dedicated database if desired. Do not delete database directories, backups or Docker volumes. Starting the same database again preserves its records; the public hostname can be reused while your account retains that assigned domain.

## 8. Common problems

| Symptom | What to check |
|---|---|
| Local health fails | Application startup, selected port and reviewed database settings; do not start a tunnel to a broken backend. |
| Security preflight fails | Start the updated app with `local,tunnel`; it must issue a Secure, HttpOnly, SameSite=Lax session cookie. Do not weaken the check or disable Secure cookies. |
| Prepared launcher rejects the database identity | Keep the reviewed workspace files in place and inspect private configuration locally; do not repoint it to an unrelated installation or create a replacement database. |
| ngrok asks for authentication | Owner completes private account/token setup; never post the token for troubleshooting. |
| Link unavailable | PC sleep, internet, Java, database and agent must all remain running; check Free usage limits. |
| First visit shows a warning | Click the provider's Visit Site button after checking the address; this is normal Free behavior. |
| Login fails through the public link | Use HTTPS, correct `local,tunnel` profile and Secure-cookie behavior; check credentials/CSRF without logging values. |
| Unrelated users share a rate limit | Inspect loopback proxy/header handling; do not trust all caller-supplied addresses. |
| Old database fails Flyway | Resume its explicit audit/migration process; a tunnel is not a baseline or data-repair tool. |

No cloud deployment, router changes, paid subscription, database exposure or recurring automation is required by this guide. Its endpoint availability depends on your PC and the tunnel provider's Free limits.
