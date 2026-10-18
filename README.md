# EV Charging — website and Spring Boot API

Java 17, Spring Boot, Spring Security, PostgreSQL, Flyway and a same-origin HTML/CSS/JavaScript website. Customers register, sign in and reserve currently available station capacity. Administrators manage stations and review operational data. Records persist in PostgreSQL across application restarts.

The agreed scope is **reserve available capacity now**, with no online charges. Reserving holds one unit until the booking is cancelled or completed. The website does not promise future appointments, payment processing, physical charger control, email delivery or SMS delivery. Notifications are actual in-app records. The retained provider classes are explicitly labeled development mocks and are not used to claim real payments or messages.

## Quick start on Windows

Install a supported JDK 17 and PostgreSQL binaries (portable ZIP or installer). Maven is provided by `mvnw.cmd`; first use requires internet access to resolve dependencies.

```powershell
# Run from the repository; substitute your actual installation directories.
.\ops\Start-LocalDatabase.ps1 -PostgresBin 'C:\path\to\pgsql\bin'
.\ops\Start-Website.ps1 -JdkHome 'C:\path\to\jdk-17'
```

The scripts create an isolated, durable PostgreSQL cluster on loopback port 55432 and run the application on port 8081. Enter an administrator email and a unique password when prompted on first startup. No shared default account or password is shipped. Open [the local website](http://127.0.0.1:8081/). Keep using the same host (`127.0.0.1`) so browser sessions remain consistent.

For an explicitly selected external PostgreSQL database, safe existing-data migration, environment variables and script behavior, read [ops/DATABASE.md](ops/DATABASE.md). Existing databases are never automatically baselined. `.env.example` contains placeholders only; Spring Boot does not load `.env` automatically.

```powershell
.\ops\Stop-Website.ps1
.\ops\Stop-LocalDatabase.ps1
```

Logs and private local data are in `.local/`, excluded from Git. Stopping preserves data. Restart with the same commands. Do not delete the local database or Docker volume as a troubleshooting shortcut.

## First use

1. Sign in with the initial administrator, create your real station records and set their total capacity. The application does not fabricate real station addresses or prepopulate public infrastructure.
2. Register a customer account and sign in. Search stations and reserve an available unit. The server derives customer identity from the session; a client cannot reserve on behalf of another customer.
3. View the reservation and cancel it when no longer needed; an administrator can mark charging completed to release capacity. Repeating the same terminal operation cannot release another unit. Current reservations hold capacity immediately; a future date/time is not requested.
4. Use the account and notification screens to manage the controls shown there. Administrative actions are enforced by the server, including when calling the API directly.

Passwords use BCrypt, sessions use HttpOnly cookies, changing a password revokes older sessions, and state-changing requests require a CSRF token. A registration request cannot grant the ADMIN role. Existing imported users with no password cannot authenticate until a deliberate provisioning process is implemented.

## Build and automated tests

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean test
.\mvnw.cmd package
```

The regular test profile uses isolated in-memory H2 in PostgreSQL compatibility mode and runs the same Flyway SQL followed by Hibernate validation. It never connects to a user's PostgreSQL database. H2 is a test dependency, not the website's persistence layer. The comprehensive report records the actual executed commands, results, PostgreSQL checks, browser checks and remaining limitations; do not infer a passing run from this README.

## Optional local Docker setup

Install Docker Desktop with Linux containers, copy `.env.example` to `.env`, and replace `DB_PASSWORD`, `APP_ADMIN_EMAIL` and `APP_ADMIN_PASSWORD` with private values. Keep `SPRING_PROFILES_ACTIVE=local` for loopback HTTP development.

```powershell
docker compose up --build -d
docker compose logs --tail 100 app
docker compose stop
```

The build runs `mvn clean verify`. The app runs as a non-root user; PostgreSQL uses a persistent volume and is not published to the host network. The app is published only on `127.0.0.1:8081`. Docker execution is an optional deployment path and is verified only if explicitly recorded in the report.

## Deployment and remaining product scope

The repository now includes a **prepared free Render demo**: [render.yaml](render.yaml) creates one Docker web service and one separate PostgreSQL database, both explicitly on the Free plan. Follow [the public demo deployment guide](docs/PUBLIC_DEMO_DEPLOYMENT_GUIDE.md). It explains account setup, secret entry, manual deployment, verification and free-plan limits. A committed Blueprint is not evidence that the service has been deployed or verified online.

The public demo uses a **fresh cloud database**. It does not connect to, overwrite, baseline or migrate the existing local `EVCHARGING` database. Preserving and migrating that database remains a separate task requiring its backup, schema audit and restored-clone rehearsal. No local accounts, station records or bookings automatically appear in the cloud demo.

Render Free web services sleep after inactivity, and Free PostgreSQL expires after 30 days. Use test data only and read the guide before creating resources. Do not choose paid resources or add billing to complete this demo. This is a public demonstration, with no availability or data-retention promise.

The code includes production-oriented access control, DTO validation, database constraints, migrations and transactional booking locking. A lasting production service still requires appropriate hosting, reliable backups, monitoring, account recovery and an operational review. Use `SPRING_PROFILES_ACTIVE=prod` behind HTTPS; that profile forces Secure session cookies. Do not switch it on for plain HTTP and expect browser login to work. The Render Blueprint activates `prod,render`, which adds the provider port, private database and bounded proxy configuration. Direct local runs ignore forwarded headers.

GitHub Actions in [.github/workflows/verify.yml](.github/workflows/verify.yml) runs Java 17 tests with H2 and disposable PostgreSQL 17, then checks/tests the dependency-free JavaScript with Node 24. Its test database is unrelated to local or cloud application data. Render automatic deployment is disabled; a maintainer must review verification before deploying a commit.

Online payment gateways, hardware charging protocols, scheduled appointments, account email verification/recovery, and external message providers are separate product milestones. They require explicit product rules and, where applicable, provider accounts. No live payment or external message is sent by this version.
