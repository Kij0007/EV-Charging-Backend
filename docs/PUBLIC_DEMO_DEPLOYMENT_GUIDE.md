# Put the website online as a free public demo

Prepared on 18 October 2026. This guide describes configuration and the checks to perform; it does not claim an online deployment already succeeded. The final task report records checks actually executed.

## 1. What this deployment does

The same Java 17 application serves the website and its JSON API. Render provides a public HTTPS address. One web-service instance connects privately to one new PostgreSQL database in the same Singapore region. No separate frontend server, Redis, paid gateway or background service is introduced.

The demo supports account registration/login, station discovery, immediate capacity reservations, cancellation, administrative completion, station management and in-app notifications. A reservation holds one unit now until cancellation/completion. It has no future appointment times, online charges or physical charger activation. The public notice asks visitors to use test information.

**Your existing local `EVCHARGING` database is separate and unchanged.** This deployment does not copy its data or credentials. Existing-data migration is still pending the backup/audit/restored-clone process in [ops/DATABASE.md](../ops/DATABASE.md). A new cloud demo starts without local users, stations or bookings.

## 2. Free-plan limits and the no-charge boundary

The Blueprint explicitly selects Free for both resources. Do not accept a paid plan, disk, add-on or upgrade. Use an eligible account/workspace without a payment method; if Render requires billing or does not offer the listed Free resources, stop and review rather than entering payment information. Existing billing-enabled workspaces need particular care because included-usage overages can be chargeable.

As documented by Render on the date above:

- The free web service sleeps after 15 minutes without inbound traffic; waking can take about a minute. Sessions are held in memory, so a restart/sleep can require login again.
- There are 750 free web-instance hours per workspace per month. Bandwidth and build-pipeline allowances also apply. Without a payment method, exhaustion can suspend services/builds.
- Free PostgreSQL has 1 GB storage, one free database per workspace, and expires 30 days after creation. It becomes inaccessible at expiry and is deleted after the subsequent 14-day upgrade grace period unless upgraded. This task does not authorize an upgrade.
- Free PostgreSQL provides no managed backups. Export useful demo data before expiry; do not rely on the grace period as a backup. The app's filesystem is ephemeral, and its files are not database storage.

These limits make this suitable for a temporary demonstration, not a continuously available service with durable customer data. Keep a calendar reminder before the database expiry; no reminder automation is created by this guide. [Render Free documentation](https://render.com/docs/free)

## 3. Files and settings

| File | Purpose |
|---|---|
| `render.yaml` | Defines two Free resources, one app instance, manual deploys, a health path, private DB references and operator-entered administrator secrets. |
| `Dockerfile` | Builds/tests with Maven and Java 17; runs as a non-root user with 50% maximum heap. It does not bake credentials into the image. |
| `application.properties` | Reads explicit `SERVER_PORT`, then hosting `PORT`, then local default 8081. Direct deployments ignore forwarding headers. |
| `application-prod.properties` | Forces Secure session cookies and gives graceful shutdown a 20-second budget. |
| `application-render.properties` | Binds all container interfaces, constructs the JDBC URL, enables private-hop proxy processing, and bounds connections/threads/sessions. |
| `.github/workflows/verify.yml` | Verifies Java/H2/PostgreSQL behavior and the frontend before manual release. It does not deploy to Render. |

`EXPOSE 8081` is image metadata, not a fixed listening port. The application reads Render's `PORT`; Docker no longer sets a conflicting `SERVER_PORT`. Render's managed edge terminates HTTPS and forwards HTTP internally. [Render web services](https://render.com/docs/web-services)

The Render profile's database URL is:

```properties
jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=require
```

The Blueprint supplies `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME` and `DB_PASSWORD` from the new database resource. A native `postgres://...` connection string is not directly usable as a Java JDBC URL. Internal TLS uses self-signed certificates; `require` encrypts traffic without falling back to plaintext, while Render does not support `verify-ca`/`verify-full` on this internal route. Both services use the same region. Public database connections are disabled with `ipAllowList: []`. [Render database connections](https://render.com/docs/postgresql-creating-connecting)

## 4. Create the resources

1. Publish/review the development branch containing these changes. Do not overwrite `main` or publish any `.env`, `.local`, database backup or private administrator file. Run the verification workflow and inspect its results.
2. Sign in to your own Render account and connect the intended GitHub repository. GitHub/Render authorization happens in your browser; never paste a login password or API token into this guide or source files.
3. Choose **New → Blueprint**, select the repository and the branch containing `render.yaml` (the development branch is `codex/backend-audit-improvements`), and use the repository-root Blueprint file.
4. Review the resource preview: `voltway-public-demo` is a **Free web service** and `voltway-public-demo-db` is **Free PostgreSQL**; both are in Singapore. No paid resources or persistent disks should appear. If either name already belongs to an unrelated resource, stop; choose unique names and update every matching reference before applying. Do not let the Blueprint adopt an existing resource unintentionally.
5. When prompted for unsynced variables, enter a new `APP_ADMIN_EMAIL` and a unique `APP_ADMIN_PASSWORD` of at least 12 characters. The password must also satisfy the application's BCrypt length limit of 72 UTF-8 bytes. These values belong in Render's secret environment controls, never Git.
6. Check that `SPRING_PROFILES_ACTIVE` is `prod,render` and `APP_DEMO_MODE` is `true`. Do not add the local `DB_URL`, local database password, fixed `SERVER_PORT`, or `JAVA_TOOL_OPTIONS` copied from another project.
7. After reviewing the preview and authorizing creation of these Free resources, apply the Blueprint. Initial creation builds/deploys once; `autoDeployTrigger: "off"` prevents subsequent branch commits from deploying automatically. Future updates require a reviewed manual deployment.

The resource properties, database references, manual-deploy flag and `sync: false` operator inputs follow the official [Blueprint reference](https://render.com/docs/blueprint-spec) and [machine-readable schema](https://render.com/schema/render.yaml.json). Cloud-account validation remains a separate check from local YAML validation.

If your workspace already has its one Free PostgreSQL instance, do not delete it or upgrade it to force this Blueprint through. Stop and choose an explicitly approved alternative. No local database should be exposed over the internet to work around a provider limit.

## 5. Understand first startup

The Docker builder executes `mvn --batch-mode --no-transfer-progress clean verify`. JVM tests must pass before the image is produced. GitHub Actions also runs the separate Node tests; the Docker image does not contain Node.

Flyway creates V1 and V2 in the new empty database, then Hibernate validates the entity mappings. `baseline-on-migrate` remains false. A nonempty unexpected database must fail startup rather than being silently adopted. The optional administrator bootstrap creates an administrator only if that email does not exist; it never overwrites an existing user's role/password.

Render checks `/actuator/health`, which exposes status without internal details and includes the database connection. Wait for application startup/Flyway/Hibernate and a healthy deployment before testing the website. A slow first build/wake is different from a failed migration. Use the logs to distinguish them; never disable migrations or change Hibernate to `update` as a shortcut.

After the first administrator is verified, remove both bootstrap variables from the service environment if desired and manually redeploy. The database account remains. Keep bootstrap values together: supplying only one intentionally fails validation. Changing bootstrap variables is not a password-reset mechanism.

## 6. Verify the public website

Use the actual HTTPS URL shown in Render, such as `https://your-service-name.onrender.com/`. The example is a placeholder, not an existing deployment.

| Check | Expected result |
|---|---|
| Open the HTTPS website | UI, CSS, icon and JavaScript load; public demo notice is visible. |
| `GET /actuator/health` | HTTP 200 and `{"status":"UP"}` when DB/application are healthy. |
| `GET /api/site-info` | `demo` is true and the demo notice is returned; no secret settings. |
| Register a test customer and sign in | Server session works; refresh retains login until session expiry/restart. Wrong passwords show an error. |
| Inspect the session cookie | HttpOnly, Secure and SameSite=Lax; credentials/tokens are not in browser localStorage. |
| Administrator creates a clearly labeled test station | Station appears publicly with actual saved capacity. No real public-charger inventory is implied. |
| Customer reserves and cancels | Capacity decreases once and returns once; only that customer's booking is accessible. |
| Administrator completes another reservation | Status changes to Completed and capacity returns once. |
| Profile/inbox/actions/help | Account update, notification-read state and content dialogs work. Ordinary users cannot execute administrator APIs. |
| Manually restart the app, then sign in again | Database records remain; in-memory login sessions need not remain. |
| Access from two independent clients | Normal login limits distinguish clients through the trusted edge; forged forwarding headers do not bypass safeguards. |

Use automated API tests for destructive/credential-changing actions before manual release. Do not delete shared demo records or change somebody else's password while testing. Record the actual outcome of these checks; source preparation alone does not prove the cloud deployment works.

## 7. Proxy, memory and availability boundaries

Only the Render profile processes forwarding headers. Tomcat walks the forwarded chain through configured RFC1918/private IPv6/loopback peers and stops at an untrusted address. It does not trust every sender. Forwarded host/port overrides are disabled because the app does not need them. Confirm the actual managed edge fits this boundary during live verification; if client IPs collapse into one limiter bucket, inspect the deployment rather than replacing the trust list with `.*`.

The Free web plan has limited memory/CPU. The image caps the Java heap at 50% of the container limit, leaving room for metaspace, native memory and threads. The Render profile uses at most five database connections, 40 HTTP worker threads, 200 TCP connections and 512 active sessions, with bounded new-session admission. These are conservative configuration choices, not a measured traffic-capacity guarantee. Cloud build/runtime performance must still be observed.

One instance is deliberate: authentication sessions and attempt/session-admission counters live in that process. Restarting resets them; the database still owns accounts, stations, bookings and notifications. Multi-instance scaling requires a deliberate shared-session/edge-abuse design. Render gives shutdown up to 30 seconds; the application drains for up to 20 seconds. Sleep, maintenance or database expiry can still interrupt this free demo.

## 8. Keep useful demo data before expiry

No managed backup is included in the Free database. If test records matter, export them before the 30-day deadline and verify restoration into a separate isolated database. A file listing from `pg_restore --list` is a preliminary archive check, not a full restore test.

The Blueprint blocks external DB access. For a deliberate local export, temporarily allow only your current public IP in the database's external-access settings, use the full external hostname and TLS, then remove that rule. Keep the external password in a secure prompt/environment variable; never in a command line or screenshot. The external route is a different hostname from the internal app route.

Example using installed PostgreSQL 17 tools, run locally after substituting only nonsecret connection details:

```powershell
$pgBin = 'C:\path\to\pgsql\bin'
$backupRoot = Join-Path $env:LOCALAPPDATA 'Voltway\PrivateBackups'
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$windowsUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $backupRoot '/inheritance:r' '/grant:r' "${windowsUser}:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not secure backup folder.' }
$backupPath = Join-Path $backupRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '.dump')
if (Test-Path -LiteralPath $backupPath) { throw 'Backup already exists; retry after a second.' }
$oldPassword = $env:PGPASSWORD
$oldSslMode = $env:PGSSLMODE
$oldConnectTimeout = $env:PGCONNECT_TIMEOUT
$secret = Read-Host 'Cloud demo database password' -AsSecureString
$credential = New-Object System.Management.Automation.PSCredential('database', $secret)
try {
    $env:PGPASSWORD = $credential.GetNetworkCredential().Password
    $env:PGSSLMODE = 'require'
    $env:PGCONNECT_TIMEOUT = '10'
    & "$pgBin\pg_dump.exe" --host 'replace-with-full-external-hostname' --port 5432 --username 'evcharging_demo' --dbname 'evcharging_demo' --no-password --lock-wait-timeout=10s --format=custom --file $backupPath
    if ($LASTEXITCODE -ne 0) { throw 'Backup failed; inspect the local error.' }
    & "$pgBin\pg_restore.exe" --list $backupPath
    if ($LASTEXITCODE -ne 0) { throw 'Archive catalog could not be read.' }
} finally {
    $env:PGPASSWORD = $oldPassword
    $env:PGSSLMODE = $oldSslMode
    $env:PGCONNECT_TIMEOUT = $oldConnectTimeout
    $credential = $null
    $secret = $null
}
```

The dump contains private account data/password hashes. Store it outside Git and public deliverables with restricted access. Do not upload it to this public demonstration repository. These example commands are instructions, not evidence that an export/restore occurred.

## 9. Troubleshoot without harming data

| Symptom | Check |
|---|---|
| No open port / deployment timeout | Bind `0.0.0.0`; allow `PORT` to reach Spring; remove any unnecessary fixed `SERVER_PORT`. |
| JDBC URL rejected | Use individual Blueprint DB references and the Render profile, not a raw `postgres://` URL in `DB_URL`. |
| DB TLS connection failure | Same-region internal hostname and `sslmode=require`; do not use internal `verify-full`. |
| Flyway reports nonempty schema without history | Wrong/reused database. Stop and audit it; do not enable automatic baselining. |
| HTTP login appears lost | Use HTTPS with the prod Secure cookie; account for cold starts/session expiry/restarts. |
| Login rate limits affect unrelated clients | Verify the managed edge's private-hop forwarding behavior. Never trust all client-provided IP headers. |
| Out-of-memory / slow startup | Inspect actual memory/build logs and free-plan limits. Do not quietly upgrade a plan or remove tests to obtain a deployment. |
| Database stopped after a month | Free expiry is expected; restore/export planning was required. No paid recovery is authorized by this guide. |

Never point this cloud app at the local `EVCHARGING` database, turn on `ddl-auto=update`, delete existing data, or apply a baseline merely to clear an error. Public-demo setup and the existing-data migration are separate workstreams.
