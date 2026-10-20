# Initial code audit — 17 October 2026

Baseline: `47872bf6572b01058a5cf75e3c3ba098a56d09f4` (the repository's only commit). Branch: `codex/backend-audit-improvements`. Written after reading every tracked source/configuration/test file and before application changes. Findings are static observations; no successful baseline build is claimed.

## Existing implementation

Java 17, Spring Boot 3.5.3, Maven wrapper 3.9.14, Spring MVC, Bean Validation, JPA, PostgreSQL and Actuator. There are three controllers, five service interfaces and implementations, three JPA repositories, three entities, two booking DTOs, two custom not-found exceptions and one exception advice. Constructor injection already exists in controllers/services and will be retained.

| Classes/files | Responsibility and current assessment |
|---|---|
| `DemoApplication`, `pom.xml`, wrapper | Conventional bootstrap; duplicate PostgreSQL dependency; retain Java/Boot versions. Wrapper scripts are tracked. |
| `AppConfig` | Selects demonstration gateways/senders using strings; misspellings silently fall back; payment setting case differs from comparison. |
| `UserController`, `UserService[Impl]`, `UserRepository` | Register/get/list implemented; no validation or duplicate protection; accepts/returns entities and client IDs. Update/delete absent. |
| `ChargingStationController`, `StationService[Impl]`, `ChargingStationRepository` | Create/get/list/availability/manual decrement implemented; invalid counters accepted; missing station becomes 500; unprotected read-modify-write. Update/delete/search absent. |
| `BookingController`, `BookingService[Impl]`, `BookingRepository` | Create/get/list/cancel implemented; fixed 500 demo price; validated request amount ignored. No transaction or locking. Cancellation repeatedly increments capacity; station update is not reliably persisted in the booking repository transaction. |
| `User` | Inverse booking list is uninitialized for new objects; `addBooking` can throw. Returning it traverses bookings and lazy station proxies. No uniqueness/null constraints for email/name. |
| `ChargingStation` | Aggregate total/available inventory, misspelled `avaialableSlots`; preserve wire compatibility. No times/connectors. No counter bounds enforced. |
| `Booking` | Owns user/station foreign keys; user eager/station lazy, string status and double amount. Preserve status spelling and fixed integer demonstration price; no general money system implied. |
| `BookingRequestDTO`, `BookingResponseDTO` | Request checks null IDs and positive amount only; response mapping is duplicated. Response DTO already avoids returning booking entities directly. |
| `PaymentGateway`, `RazorpayGateway`, `StripeGateway`, `PaymentService[Impl]` | Console demonstrations returning true; no SDK, credentials, verification, persistent payment state, refund or idempotency. These are not real integrations. |
| `NotificationSender`, `EmailNotificationSender`, `SmsNotificationSender`, `NotificationService[Impl]` | Console demonstrations, no recipient or provider. Called before a transaction boundary; sender failure can surface after partial writes. |
| Exceptions and `GlobalExceptionHandler` | User/booking 404 strings and validation maps only. No consistent error shape, station not-found or conflict handling. |
| `application.properties`, `.gitignore`, `pom.properties` | PostgreSQL despite file-database comment; committed literal database password, automatic schema update, SQL logging, irrelevant H2 console. `.env.example` would be ignored by current patterns. `pom.properties` duplicates artifact metadata. |
| `DemoApplicationTests` | Single context test in `com.example.demo`, outside application package: Boot configuration discovery is incorrect. No isolated DB or business tests. |

## Confirmed problems and priority

| ID | Priority | Evidence / impact | Planned action |
|---|---|---|---|
| SEC-01 | P0 | Literal DB password committed in application configuration and sole reachable Git commit. | Remove from current configuration, environment placeholders; explicitly require rotation and controlled history remediation. Never reproduce its value. |
| BK-01 | P1 | Booking checks availability then saves/decrements without one transaction or lock. Concurrent requests can reserve beyond capacity; failures leave partial state. | Transaction plus database station row lock for all capacity mutations; isolated concurrency tests. |
| BK-02 | P1 | Cancellation unconditionally increments available capacity and lacks shared transaction. | Lock status and station, idempotent cancellation, counter bounds, rollback tests. |
| API-01 | P1 | User/station entity inputs allow IDs, invalid data and nested relationships; outputs expose graph/proxies. | Request/response DTOs, validation, service checks, explicit not-found/conflict responses. |
| USR-01 | P1 | Duplicate emails accepted; new user's booking list can be null. | Normalize/validate email with unique DB constraint and safe collection initialization; safe deletion refuses referenced records. |
| TEST-01 | P1 | Test package cannot discover application; PostgreSQL credentials required for test context. | Correct test configuration, H2 isolated test DB; PostgreSQL-specific verification if locally available. |
| INT-01 | P1 | Provider classes claim messages/payments occurred, always succeed; notifications happen during booking writes. | Explicit mock mode, validated provider selection, payment failure behavior and after-commit best-effort notification tests. No live external calls. |
| ERR-01 | P2 | Inconsistent error bodies and generic 500s for domain failures. | Unified sanitized errors with field details, path and timestamp. |
| CFG-01 | P2 | `ddl-auto=update`, SQL logging, H2 console flags and duplicate Maven dependency. | Explicit dev/production config; production validates schema; migration instructions without touching existing data. |
| CRUD-01 | P2 | Update/delete/search and booking filters absent. | Small additions preserving original endpoint paths and documented wire fields; block deletion with booking history. |

## Domain and security boundaries

The existing model counts available station slots. It has no booking times, connector identity, charging duration or completion policy. Implement inventory concurrency; do not invent overlapping time reservations. Preserve the existing server-side 500 demonstration price; incoming amount remains a compatibility field, never authority for billing. Document its behavior.

There is no authentication or ownership model. Treat this as a private local educational API: loopback default binding, no permissive CORS, no real payment/notification integrations. Internet deployment remains blocked on a documented identity/role/ownership design; adding ad hoc login is out of scope. Manual decrement remains a compatibility operation but must use the same station lock and validation, and is unsuitable for public access.

Keep PostgreSQL, Java 17 and Boot 3.5.3. Use only isolated databases during tests; do not connect to or mutate an existing database. Database constraints require a preflight audit of existing duplicates, nulls and invalid counters. No automatic destructive migration, cascading booking deletion, infrastructure platform, general payment system or speculative scheduling feature is planned.

## Verification plan

Run wrapper baseline if a suitable JDK can be used, then execute focused unit/controller tests and integrated booking/rollback/concurrency tests. Record exact commands and observed results. Current machine exposes a Java launcher and JDK 21 installation; Java 17 runtime availability is still being checked. Compile to the declared Java 17 release and clearly distinguish that from executing on JDK 17. Review the full diff and generate the comprehensive educational report from baseline source and actual results.
