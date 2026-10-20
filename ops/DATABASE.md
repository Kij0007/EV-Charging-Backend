# Database setup and safe migration

The application retains PostgreSQL. Flyway applies versioned SQL before Hibernate validates the entity mappings. Automatic Hibernate schema updates and automatic Flyway baselining are disabled in every shipped profile. No script connects to an existing user database automatically.

## Fresh local database

Install JDK 17 and PostgreSQL 17 binaries. The official Windows download page links the installer and portable ZIP: [PostgreSQL for Windows](https://www.postgresql.org/download/windows/). A ZIP can be extracted without installing a Windows service. PostgreSQL 14 or later supports the SQL used here, but use a supported and patched release for deployment.

From the repository in PowerShell:

```powershell
.\ops\Start-LocalDatabase.ps1 -PostgresBin 'C:\path\to\pgsql\bin'
.\ops\Start-Website.ps1 -JdkHome 'C:\path\to\jdk-17'
```

The first command creates a new cluster under `.local/postgres/data`, a randomly generated database password, and a database called `evcharging`. It uses `127.0.0.1:55432` and SCRAM authentication. The credential directory is restricted to the current Windows user. No password is printed. The database is persistent and is reused on subsequent starts. A preexisting unmarked directory or occupied port makes the script stop; it does not take over another database.

The website script reads the private local credential file, runs the build and tests, prompts for an initial administrator if requested, and launches the executable jar in the background. It restores the calling process's environment after launch. Use `-SkipBuild` only after a successful build. Logs and process metadata are under `.local/` and ignored by Git.

```powershell
.\ops\Stop-Website.ps1
.\ops\Stop-LocalDatabase.ps1
```

These commands stop only the recorded application process and the marked local PostgreSQL cluster. They preserve all data. Do not delete `.local/postgres` to solve a startup problem; inspect the logs first.

## An explicitly selected PostgreSQL database

If you manage PostgreSQL yourself, create a **new, empty** database and a dedicated database owner. Set the following process environment variables. Spring Boot does not automatically read `.env` files.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/evcharging_new'
$env:DB_USERNAME = 'evcharging'
$env:DB_PASSWORD = 'replace_with_your_unique_database_password'
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:APP_ADMIN_EMAIL = 'your-admin@example.com'
# Supply a unique password via your secret manager or a secure prompt.
$secret = Read-Host 'Initial administrator password' -AsSecureString
$credential = New-Object System.Management.Automation.PSCredential('bootstrap', $secret)
$env:APP_ADMIN_PASSWORD = $credential.GetNetworkCredential().Password
.\mvnw.cmd clean package
& "$env:JAVA_HOME\bin\java.exe" -jar .\target\demo-0.0.1-SNAPSHOT.jar
```

Do not reuse placeholder credentials. For deployment, provision a restricted runtime role separately from the migration role once your operations process supports separate migration execution. The default simple configuration uses one role that owns this application's schema.

## What the migrations do

| Migration | Purpose |
|---|---|
| `V1__audited_backend_baseline.sql` | Recreates the schema contract at commit `a6c1923`: users, aggregate station capacity, bookings, foreign keys, indexes, unique email and request keys, and named data checks. |
| `V2__accounts_and_local_reservations.sql` | Adds nullable BCrypt password hashes, role and credential version; booking creation timestamps and Completed state; allows zero-cost reservations; creates durable in-app notifications. |

Existing imported users receive role `USER` and a null password hash. They cannot sign in until a separately authorized account provisioning or recovery process assigns a password. The application never invents passwords or automatically claims a legacy account by email. New accounts store BCrypt hashes. Historical bookings retain their original amount and status. Their `created_at` is the migration execution timestamp because the old model did not record their real creation time; do not interpret that backfill as a historical fact.

The spelling `avaialable_slots` is deliberately retained for compatibility. Stations have aggregate capacity; no future start/end times, named connectors, or actual charger hardware protocol are introduced.

## Existing database: explicit human review required

**Never point the new application at existing data and enable `baseline-on-migrate`.** An existing nonempty schema without Flyway history must fail startup until a reviewed baseline is installed. No existing or production data was accessed or migrated during this task.

1. Rotate the previously committed database credential and follow the original report's controlled history-remediation guidance.
2. Take a PostgreSQL backup and prove that it restores into an isolated database. Test every following step against that restored copy first. Do not run a `DROP`, delete data, or reset identity sequences as a shortcut.
3. Confirm that the database already matches **the audited `a6c1923` schema**, not the older original repository schema. If it predates that commit, first review the schema/data compatibility procedure in the original change report. Resolve invalid rows and duplicates through an agreed business decision.
4. Inspect tables, column types, nullability, unique constraints, foreign keys and indexes against V1. The expected relationships are `booking.user_id → users.id` and `booking.station_id → charging_stations.id`. Existing IDs and sequence positions must be preserved. Verify there are no orphan records and inventory is within bounds.
5. Inspect the original booking check name using this read-only query:

```sql
SELECT conname, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'booking'::regclass AND contype = 'c';
```

V2 replaces a **named** check, `ck_booking_validity`. A Hibernate-created database may have a different name. After reviewing its exact definition, rename the original combined `amount > 0` and status check to `ck_booking_validity`. If the old schema has separate checks, consolidate them into the V1 combined definition in a reviewed transaction; do not leave a positive-amount check behind, or zero-cost reservations will still fail. Resolve any other schema differences before baselining. The names of unrelated existing foreign keys/unique constraints do not affect Hibernate validation, but their semantics must match V1.

6. Install the official Flyway CLI or use your organization's approved migration tool. Set credentials as environment variables rather than printing them or passing a password on the command line:

```powershell
$env:FLYWAY_URL = $env:DB_URL
$env:FLYWAY_USER = $env:DB_USERNAME
$env:FLYWAY_PASSWORD = $env:DB_PASSWORD
$env:FLYWAY_LOCATIONS = 'filesystem:src/main/resources/db/migration'
flyway -baselineVersion=1 -baselineDescription='Reviewed audited a6c1923 schema' baseline
flyway info
flyway migrate
flyway validate
```

The baseline marks V1 as already present; it **does not create, inspect or repair** the old schema. V2 is applied normally. Run these commands from the repository only after the checks above. Verify the Flyway version used is compatible with the application and PostgreSQL.

7. Start the new application against the restored database and verify health, administrator access, user access, booking/cancel/complete flows, capacity and ownership. Only after that rehearsal and an approved rollback plan should you schedule the real migration.

## Production deployment boundary

The `prod` profile always uses Secure, HttpOnly, SameSite=Lax session cookies and requires HTTPS at the trusted reverse proxy. Configure a unique administrator password and database credentials in a secret manager. Limit database network access, schedule backups, test restores, monitor disk/connection usage, and define retention and incident-response procedures. Deploy the application behind TLS; never expose the PostgreSQL port publicly. The supplied Compose file is a local loopback deployment and has no TLS proxy.

Use `docker compose stop` to stop the local containers while preserving the named database volume. Do not run `docker compose down -v` unless you have explicitly decided to permanently destroy that local database and have a backup.
