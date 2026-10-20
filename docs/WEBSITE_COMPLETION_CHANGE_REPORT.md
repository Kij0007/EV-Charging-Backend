# Voltway: From Backend API to Working EV Charging Website

**Change report and learning guide | 17-20 October 2026**

Repository: https://github.com/Kij0007/EV-Charging-Backend
Comparison point: audited backend commit `a6c1923`
Development branch: `codex/backend-audit-improvements`

The final implementation commit is recorded in the delivery message after the report is frozen.

This report covers the second development phase: the website, real account login, access control, durable notifications, database migrations and operational setup. The earlier `docs/EV_CHARGING_BACKEND_CHANGE_REPORT.md` explains the first backend audit and remains part of the repository. File paths below are relative to the repository root. Code shown as "before" comes from the actual `a6c1923` Git version; new files are explicitly identified as new.


**Current status, 20 October:** The selected setup runs the website and PostgreSQL on the owner's Windows PC, reached through the account-assigned Free ngrok domain. The complete local application/health/Secure-cookie preflight passed at 10:57:33 IST, then the verification process was stopped. A later read-only public probe returned HTTP 404 with provider error `ERR_NGROK_3200`: the assigned public endpoint was offline at that check. The owner must run the full launcher and complete its hidden private token step before public connectivity can be checked again. The Render web service remains suspended and its separate Free database was retained, not deleted. WEB-015 in Section 20 explains the current evidence. Existing local `EVCHARGING` data has not been migrated or copied.

**How to read the earlier results:** Sections 1-14 describe the original local release; Sections 15-19 record the subsequently approved cloud-demo preparation and historical Render rollout. Those results remain actual evidence from their checkpoints, but the old Render URL is not the current link to share. The current PC-tunnel source passed 148 Java tests and packaged successfully; the final public ngrok startup and user journey still need their own observed results.

## 1. What was delivered, and what the application does

The starting project was a Java 17 Spring Boot backend with user, station and booking APIs. The previous audit had already added DTOs, validation, safe updates/deletion, transactional capacity locking and tests. It had no website or login. Its payment and message providers were demonstrations.

The updated application serves a complete browser website and its JSON API from one Spring Boot process. Customers create accounts, sign in, find stations, reserve current capacity, see their bookings, cancel an active reservation, edit their profile, change their password and read in-app notifications. Administrators manage stations, see customers and all bookings, cancel reservations and mark them complete. The browser displays actual server records; authentication and bookings are persisted and checked by the backend.

Two product decisions were explicitly approved: **reserve available capacity now** and **run locally without online charges**. A reservation consumes one unit immediately and holds it until cancellation or administrator completion. There is no future start/end time, automatic expiry, named connector, online checkout or physical charger activation. These limits are stated in the interface and its help content.

"Production-oriented" here describes implemented engineering controls: password hashing, sessions, role and ownership checks, CSRF, migrations, database constraints, safe errors and concurrency protection. At the original local-release checkpoint, hosting and TLS had not been deployed. The later Free Render rollout and online checks in Sections 18-19 are historical: that web service is now suspended. The current PC-hosted HTTPS-tunnel approach is explained in Section 20. Operational monitoring, real charging equipment and payment-provider accounts remain outside this application. A commercial public service still requires the operational and product work in Section 12.

| Area | Implemented behavior | Deliberate boundary |
| --- | --- | --- |
| Accounts | Registration, login/logout, profile update, password change and old-session revocation | No email verification, forgotten-password email or MFA |
| Authorization | USER/ADMIN roles; ownership enforced on private booking data | No public role editor or automatic promotion |
| Stations | Public discovery; search, sort and availability filters; administrator create/edit/delete | Station records are operator-maintained; no fabricated live network |
| Reservations | Immediate capacity hold, idempotency key, cancellation and completion | Aggregate capacity; no future appointments or charging telemetry |
| Notifications | Persisted private inbox with unread/read state | No actual email or SMS |
| Payments | New reservations store amount zero and collect no money | Legacy provider examples remain isolated demonstrations |
| Website | Responsive screens, dialogs, validation, loading/error/empty states and connected actions | External account-recovery and payment actions are not advertised |
| Persistence | PostgreSQL configuration, Flyway migrations and Hibernate validation | Existing databases require reviewed migration |

## 2. Architecture: how the pieces fit together

The application remains one understandable Spring Boot application. There is no frontend framework build, separate Node web server, Redis, Kafka or microservice deployment.

```text
Browser: index.html + assets/app.js + assets/api.js + assets/content.js
    |
    | same-origin HTTP + session cookie + CSRF header on writes
    v
Spring Security filters: login requirement, role checks, session verification
    |
    +--> AuthController --> AuthService --> UserRepository --> PostgreSQL
    |
    +--> WebsiteApiController --> CurrentUser / existing domain services
                                  |
                                  +--> BookingServiceImpl
                                  |     station row lock + booking + inbox
                                  +--> StationService / UserService
                                  +--> InboxService --> UserNotificationRepository
    |
    v
Safe response DTOs --> real screen updates
```

Flyway runs versioned SQL before Hibernate validates mappings. Entity relationships stay inside the backend; controllers map them to DTOs. The browser gets account information such as name and role, never password hashes or Hibernate entity graphs.

The first audit's locking strategy is retained. Booking creation locks the user and station, checks capacity while holding the station lock, writes the booking and decrements capacity in one transaction. Cancellation and completion follow the same station-lock protocol. An administrative station resize also respects occupied capacity.

## 3. Real login and account security: class by class

### WEB-001: User identity and password storage

**Changed:** `entity/User.java` and `repository/UserRepository.java` under `src/main/java/com/evcharging/`.

The old user had an ID, name, email and booking relationship, but no credential or role. `User` now adds a nullable `passwordHash`, a role restricted to `USER`/`ADMIN`, and an integer `credentialVersion`. The repository adds `findByEmailIgnoreCase` for authentication. New passwords are encoded with BCrypt cost 12. There is no plaintext password column. `@JsonIgnore` protects the hash even if an entity is accidentally serialized; normal APIs still use DTOs.

A migrated user with no password hash cannot log in. Merely knowing an old account's email is insufficient to claim its bookings. Migration assigns USER/version 0 without inventing a password. Provisioning or recovery of those historical accounts is a separate, authorized task.

**Trade-off:** BCrypt deliberately uses CPU to make stolen-hash guessing more expensive. Passwords must contain at least 12 characters and at most 72 UTF-8 bytes. The byte limit matters because one Unicode character can require several bytes; silently truncating a long password would be unsafe.

BCrypt adds a random salt and performs a one-way computation. During login, `PasswordEncoder.matches()` checks the supplied password against the stored hash; the application has no decoding operation that recovers the original password. Even two users choosing the same password normally receive different stored hashes. Returning a hash to the browser would still expose material useful for offline guessing, so it remains private.

### WEB-002: Authentication package

All following files are new in `src/main/java/com/evcharging/auth/`; there is no previous implementation to display.

| Class / file | Responsibility and meaningful behavior |
| --- | --- |
| `AuthController.java` | Exposes CSRF, register, login, current-account and password-change endpoints. Login explicitly saves the security context to the session and rotates the session ID and CSRF token. Logout is supplied by Spring Security. |
| `AuthService.java` | Normalizes account names/emails, checks duplicates, stores BCrypt hashes and maps safe responses. Password changes lock the user row, verify the old password, reject an unchanged password and increment the credential version in one transaction. |
| `AuthUserDTO.java` | Returns only `id`, `name`, `email` and `role`. It never returns credentials, a credential version or booking relationships. |
| `RegisterRequest.java` | Allows name/email/password only, with required values, bounded lengths, email validation and a minimum password length. Registration always creates USER. |
| `LoginRequest.java` | Bounds email/password input. Incorrect passwords, absent accounts and disabled legacy accounts receive the same generic credential failure. |
| `ChangePasswordRequest.java` | Separates the current password from the new password and applies the same new-password constraints as registration. |
| `PasswordPolicy.java` | Enforces the BCrypt UTF-8 byte limit explicitly. Passwords are neither trimmed nor logged. Oversized login/current-password input is rejected safely. |
| `AuthenticationAttemptLimiter.java` | Limits attempts by operation and actual remote IP: 10 logins per five minutes, five registrations per hour and five password changes per five minutes. Expired buckets are removed; storage is capped at 10,000 buckets. |
| `TooManyAttemptsException.java` | Carries a retry interval without credentials or sensitive account information. |
| `AuthExceptionHandler.java` | Returns consistent JSON 401 credential errors and JSON 429 throttling errors with `Retry-After`. Other validation/business errors use the existing common handler. |
| `AdminBootstrap.java` | Optionally provisions an initial administrator from `APP_ADMIN_EMAIL` and `APP_ADMIN_PASSWORD`. Invalid/incomplete supplied values fail validation. An existing email is never promoted, reset or replaced. |

The attempt limiter is process-local and bounded. It resets when the process restarts and is not shared between application replicas. Many customers behind the same network can share a limit. A public deployment should add an edge/shared limiter and a deliberate trusted-proxy strategy.

No default administrator password is shipped. After successful first provisioning, remove the bootstrap credentials from the deployment environment and use the persisted administrator account. Supplying another password to bootstrap does not reset that account.

### WEB-003: Security package and safe errors

All following classes are new in `src/main/java/com/evcharging/security/`.

| Class / file | How it protects the application |
| --- | --- |
| `SecurityConfig.java` | Defines public routes explicitly, requires login elsewhere, protects administrative routes, enables CSRF and uses server-side session authentication. Unknown routes default to denied. Adds a same-origin Content Security Policy; Spring security headers retain anti-framing and content-type protections. |
| `AccountUserDetailsService.java` | Loads the account by email and supplies a `UserPrincipal` to the DAO authentication provider. It does not retain a JPA entity in the session. |
| `UserPrincipal.java` | Stores identity, role, enabled state and credential version. Its temporary password hash is transient and erased after authentication. Erasing the hash does not disable an established session. |
| `CurrentUser.java` | Gets trusted identity from Spring Security. `id()`, `requireOwner()` and `requireAdmin()` centralize ownership/role checks. Administrators may manage another user's bookings; ordinary users may not. |
| `SessionAccountFilter.java` | Rechecks the account/version on authenticated requests. A deleted account, changed password or mismatching role invalidates the session. Existing sessions are revoked on their next request; an already-running request is not interrupted. |

`GlobalExceptionHandler.java` is modified to return 401/403 for authentication/authorization exceptions instead of treating them as internal errors. The filter chain also returns JSON errors for requests rejected before a controller runs. Errors keep the existing `timestamp`, `status`, `code`, `message`, `path` and `fieldErrors` structure. Passwords, stack traces and SQL details are not included in public responses.

**Why sessions:** this is one same-origin browser application. A server session and HttpOnly cookie keep credentials out of JavaScript storage. A JWT subsystem or distributed session store would add complexity without serving the agreed local scope. Sessions expire after 30 minutes of inactivity; only cookie-based tracking is allowed. Production cookies are Secure, HttpOnly and SameSite=Lax.

CSRF protection addresses a separate issue: browsers attach session cookies automatically, so a malicious site can try to trigger a write using a victim's session. The application requires a session-bound security token in a request header. Its own frontend obtains that token, while an unrelated website cannot read it through the same-origin browser boundary. Login rotates both session identity and token; the frontend therefore refreshes its cached token.

## 4. Authorization: who can do what

Hiding a button improves usability; the server performs the actual security check. A customer manually calling an administrative API is still rejected.

| Action | Guest | USER | ADMIN |
| --- | --- | --- | --- |
| Open website, read public `/api/stations` | Yes | Yes | Yes |
| Register/login and fetch a CSRF token | Yes | Available | Available |
| Read/update own profile; change own password | No | Yes | Yes |
| Reserve capacity for the signed-in account | No | Yes | Yes |
| Read own bookings; cancel own confirmed booking | No | Yes | Yes |
| See another customer's bookings or all bookings | No | No | Yes |
| Complete a booking | No | No | Yes |
| Add, edit or delete an unused station | No | No | Yes |
| Read customer directory or legacy `/users/**` | No | No | Yes |
| Read/mark own inbox records | No | Yes | Yes |
| Read another person's inbox | No | No | No dedicated administrative inbox API |

`BookingController.java` now checks ownership on legacy creation, reads, per-user lists and cancellation. All-booking and station-booking lists require ADMIN. `WebsiteApiController` derives the customer ID from the session when reserving; its request DTO contains only `stationId`. A supplied `userId` or `role` is rejected as an unknown JSON field rather than trusted.

Legacy paths are retained, with an intentional security change: previously anonymous protected operations now require a session and CSRF on writes. The legacy `POST /users/reg` is an administrative data-creation API and still has no password input; use `POST /api/auth/register` for a new account that can sign in. This distinction avoids silently changing its old request shape.

## 5. Reservations and real in-app notifications

### WEB-004: BookingServiceImpl, BookingService, Booking and BookingResponseDTO

The previous audited implementation called a demonstration payment gateway with a hardcoded amount of 500. That did not prove a real charge. The approved release collects no online payment, so new bookings now store amount zero and no payment call is made. Historical amounts are preserved.

**Before: actual focused `BookingServiceImpl.createBooking` snippet at `a6c1923`:**

```java
if (!paymentService.processPayment(DEMO_AMOUNT)) {
    throw new PaymentFailedException("Mock payment was declined; no booking was created");
}
Booking booking = new Booking(user, station);
booking.setBookingStatus(Booking.CONFIRMED);
booking.setAmount(DEMO_AMOUNT);
```

**After: focused current implementation:**

```java
Booking booking = new Booking(user, station);
booking.setBookingStatus(Booking.CONFIRMED);
// This release reserves capacity without collecting online payments.
booking.setAmount(0);
booking.setRequestKey(requestKey);
station.setAvaialableSlots(station.getAvaialableSlots() - 1);
Booking saved = bookingRepository.saveAndFlush(booking);
inbox.bookingNotice(user, "Booking #" + saved.getId() + " confirmed at "
        + station.getStationName() + ". No online payment was collected.");
```

Both snippets omit surrounding validation and locking. The station lock still protects the check/decrement/save operation. `completeBooking()` is added to the service interface and implementation. Cancellation and completion share `finishBooking()`: locate the station, lock its row, then load the booking's current state. Repeating the same completed operation returns without releasing more capacity. Attempting to change a Completed booking into Cancelled, or vice versa, produces a conflict.

`Booking.java` adds `COMPLETED`, an immutable `createdAt` timestamp and a check allowing zero amounts and the three supported states. `BookingResponseDTO.java` retains the old fields and adds `stationName`, `location` and `createdAt`. The website can show a useful booking card without loading and exposing an entity graph.

**Concurrency example:** station capacity is one. Alice and Ben reserve simultaneously. Alice acquires the station row lock; Ben waits. Alice writes one booking, reduces availability to zero and commits. Ben then reads zero under the same lock and receives a conflict. If Alice's transaction fails, her booking, counter change and inbox entry roll back together, so Ben can still obtain the slot. This is an immediate-capacity guarantee, not a future time-slot guarantee.

The existing optional `Idempotency-Key` support remains. A retry with the same key and same account/station returns the original booking, including its current terminal status. Key reuse for a different request conflicts. The frontend keeps one UUID for an open reservation attempt, including retry after an uncertain network result. A full browser reload loses that in-memory attempt key; the user should first check My bookings before starting a new reservation after an uncertain result.

### WEB-005: Durable notifications and isolated provider demonstrations

New `UserNotification.java` stores user, message, creation time and read state. New `UserNotificationRepository.java` lists the user's most recent 100 entries and finds a notice by both ID and owner. New `InboxService.java` maps safe notice DTOs and marks owned records as read.

`InboxService.bookingNotice()` uses `Propagation.MANDATORY`: it must join an existing booking transaction. It cannot accidentally create an independent notification before the booking commits. Because this is a database inbox rather than an external message, storing it in the same transaction is appropriate: either booking/capacity/notice all succeed or none do. Other connections cannot see the notice before commit. Marking an already-read notice again is harmless.

This deliberately replaces the normal application's earlier best-effort mock-sender event flow. `AppConfig.java`, `BookingNotificationListener.java`, `NotificationServiceImpl.java` and `PaymentServiceImpl.java` are now restricted to the `integration-demo` profile. Their strategy examples and tests remain available for learning, but they do not run in the normal website profiles. Razorpay/Stripe/email/SMS classes remain explicit demonstrations, with no claimed provider integration. Enabling that demonstration profile does not make those providers real.

**Trade-off:** if database storage of an inbox record fails, the reservation rolls back. That is intentional consistency for a same-database record. Future external email/SMS needs a separately designed durable delivery/outbox process; it should not be added as a network call while holding the station lock.

## 6. The website and all visible controls

### WEB-006: Frontend files created from scratch

There were no website/content files in the audited baseline. The following files are all new in `src/main/resources/static/`.

| File | What was created and why |
| --- | --- |
| `index.html` | Accessible application shell, navigation hooks, main content, native dialog, local icon symbols and toast status area. Spring Boot serves it at `/`. |
| `assets/styles.css` | Responsive Voltway design, forms, station/booking cards, operator tables, dialogs, small-screen navigation, reduced-motion behavior and a CSS charging illustration. No external font or CSS service is required. |
| `assets/app.js` | All screens, session-aware navigation, server data loading, action handlers, forms, confirmation dialogs and helpful validation/error/empty states. User-controlled text is escaped before HTML insertion. |
| `assets/api.js` | One consistent HTTP client: same-origin cookies, CSRF acquisition/rotation, JSON parsing, safe API errors, session-expiry events and a 30-second timeout. Writes are not blindly retried. |
| `assets/content.js` | Separate Help, Privacy & cookies and Booking guide content, plus status-aware reservation feedback. Copy states what a reservation provides, what data is stored, cookie use and the no-online-payment boundary. |
| `assets/favicon.svg` | Local Voltway lightning icon for the browser tab. No remote image service is required. |

The final visual revision follows the user's choice of **calm blue/navy with soft coral accents**. `assets/styles.css` uses mist-blue `#f2f6fc` surfaces, navy `#192f52`, denim-blue `#395f94` controls and coral `#f4b8ab` highlights. Text, hero gradients, station illustrations, focus rings, status chips, notifications and errors share the palette. `assets/favicon.svg` adopts navy/coral, and `index.html` sets the browser theme color to navy. This changes presentation while retaining the booking rules, APIs and working controls. Representative calculated contrast pairs include white text on the primary button at 6.48:1 and dark text on coral at 6.90:1; selected palette checks do not constitute a complete accessibility certification.

A browser-observed accessibility issue was also fixed: at the narrow breakpoint, CSS hid visible button text and left Help/account controls unnamed in the accessibility tree. `index.html` now provides explicit accessible names; `renderNavigation()` in `app.js` updates Sign in/Sign out and navigation names as the session changes. The mobile account control names Account settings and the current user; decorative sidebar SVGs are hidden from assistive technology. Accessible names remain available when visual labels are hidden.

`app.js` does not create browser-only bookings or save login tokens in local storage. `refreshData()` fetches server resources, maintains independent errors and uses a generation counter so an older response cannot repopulate personal data after logout. `esc()` escapes names, station details and notice text. The Content Security Policy provides an additional layer; it does not replace escaping.

The same session-generation protection covers slow mutation, customer-list and inbox responses: a request begun before logout cannot later reopen a booking dialog or repopulate private data. Account forms warn before navigation discards edits; dirty station dialogs warn before closing. Browser navigation/closing also warns about unsaved forms. Closed dialog markup is removed so password inputs are not retained in a hidden modal.

`content.js` contains 14 authored sections: five Help sections, four Privacy & cookies sections and five Booking guide sections. Page labels, form explanations, empty/loading/error states and confirmation copy are authored in `app.js`/`index.html`. Input examples are guidance, not invented station or customer records.

A review caught an important replay edge case: the backend may return an existing Cancelled/Completed booking for an idempotent retry, so the confirmation must not hardcode "Confirmed". `reservationFeedback(status)` now derives the title, active state and status text from the actual booking. The reserve handler prefers successfully refreshed history, falling back to the mutation response when needed. Three regression tests ensure terminal replays never claim an active reserved space. The password-change response also checks the session generation before clearing the UI, so an older response cannot sign a newly established screen out.

### Visible controls and their real actions

The table describes implemented handlers. Section 10 separately states what was actually executed during verification; a handler's presence is not automatically a completed browser test.

| Screen / control | Connected result |
| --- | --- |
| Discover / search / Clear filters | Reads actual stations and matches name/location; restores the full list when cleared. |
| Available now / sort / Refresh | Filters full stations, sorts by selected field and reloads server data. |
| Station details | Shows the selected record's real location, total/available capacity and reservation rules. |
| Reserve / confirmation | Guest is asked to sign in; authenticated submit creates a real booking with an idempotency key and shows its returned ID. Capacity conflicts refresh the station data. |
| Sign up / Sign in / show password | Creates and authenticates an account through Java APIs; show/hide changes only password visibility. Wrong credentials remain a form error. |
| My bookings / status filters / details | Displays the user's persisted history, location, status and timestamp. |
| Customer Cancel | Confirms intent, calls the protected cancellation API and refreshes capacity/history/inbox. |
| Administrator All bookings / Cancel / Complete | Reads all reservations; authorized terminal actions update the real booking and release one unit. |
| Administrator Add/Edit station | Writes validated station details. New station capacity starts available; edits let the service derive available capacity from occupied capacity. |
| Administrator Delete station | Deletes an unused record; a station with booking history returns a clear conflict. |
| Administrator Customers | Reads safe account details. It is explicitly read-only, with no unimplemented role-edit control. |
| Account / Save changes | Updates the signed-in user's name/email, preserving role and ownership. |
| Account / Change password | Verifies the current password, changes the hash/version and signs the browser out for reauthentication. |
| Inbox / Mark read | Reads persisted owned notices and updates read state in the database. |
| Help / Privacy & cookies / Booking guide | Opens content from the actual `content.js` module. Dialog close, confirmation cancel and backdrop/keyboard interactions are wired. |
| Sign out | Invalidates the server session, clears personal UI state and resets the CSRF cache. |

A successful data mutation refreshes the relevant screens. Buttons show progress and errors rather than silently fabricating success. The customer cannot complete charging; completion belongs to an administrator. Navigation visibility follows the role, and the backend independently enforces that role.

## 7. Controllers, DTOs and API reference

### WEB-007: WebsiteApiController and compatibility

`WebsiteApiController.java` is new. It exposes focused browser routes while reusing the existing domain services. It contains a validated `ReservationRequest(stationId)` and safe `Account(id,name,email,role)` record. Existing user/station DTOs and services are reused where their behavior is already appropriate; unchanged classes were not rewritten merely to increase the change count.

All writes need a valid CSRF token. Authenticated routes also require the session cookie. Unless noted, validation errors return 400, authentication errors 401, denied operations 403, missing records 404 and conflicting state/duplicates 409. Throttled authentication operations return 429. The normal success response is 200 unless another status is listed.

| Method and new endpoint | Request / result | Access |
| --- | --- | --- |
| `GET /api/auth/csrf` | Returns `{token,headerName}`; browser sends that header on writes. | Public |
| `POST /api/auth/register` | `{name,email,password}`; safe account DTO, 201. Does not itself log in. | Public + CSRF |
| `POST /api/auth/login` | `{email,password}`; safe account DTO and session cookie. | Public + CSRF |
| `GET /api/auth/me` | `{id,name,email,role}` for the current account. | Signed in |
| `PUT /api/auth/password` | `{currentPassword,newPassword}`; 204 and current-session invalidation. | Signed in |
| `POST /api/auth/logout` | No body; 204, session invalidated and cookie removed. | CSRF required |
| `GET /api/stations?query=...` | List of station DTOs; query matches name/location. Browser also filters/sorts this data. | Public |
| `POST /api/stations` | `{stationName,location,totalSlots,avaialableSlots}`; station DTO, 201. | ADMIN |
| `PUT /api/stations/{id}` | `{stationName,location,totalSlots}`; updated station DTO. | ADMIN |
| `DELETE /api/stations/{id}` | No body; 204 if history permits deletion. | ADMIN |
| `GET /api/bookings` | USER receives own bookings; ADMIN receives all. | Signed in |
| `POST /api/bookings` | `{stationId}` plus optional `Idempotency-Key`; booking DTO, 201. Identity is derived from session. | Signed in |
| `POST /api/bookings/{id}/cancel` | No body; updated booking DTO. | Owner or ADMIN |
| `POST /api/bookings/{id}/complete` | No body; updated booking DTO. | ADMIN |
| `GET /api/profile` | Current safe account DTO. | Signed in |
| `PUT /api/profile` | `{name,email}`; updated safe account DTO. | Signed in, own account |
| `GET /api/admin/users` | Safe account list with roles. | ADMIN |
| `GET /api/notifications` | Up to 100 `{id,message,createdAt,read}` records belonging to the current user. | Signed in |
| `PUT /api/notifications/{id}/read` | No body; 204 if owned; another user's notice is treated as not found. | Owner |

**Actual response shapes, with illustrative values:**

```json
{"id":1,"name":"Driver","email":"driver@example.com","role":"USER"}
```

```json
{"bookingId":1,"userId":1,"stationId":3,"bookingStatus":"Confirmed",
 "amount":0.0,"stationName":"Central EV","location":"Pune",
 "createdAt":"2026-10-17T12:00:00Z"}
```

```json
{"timestamp":"2026-10-17T12:00:00Z","status":403,"code":"ACCESS_DENIED",
 "message":"You do not have permission to perform this action",
 "path":"/api/bookings/1/complete","fieldErrors":{}}
```

IDs, names and timestamps above are examples, not claims that those production records exist. The station DTO retains the historical `avaialableSlots` spelling; changing it would unnecessarily break the old contract.

### Retained legacy endpoint contract

| Existing endpoint | Current behavior / access |
| --- | --- |
| `POST /users/reg` | `{name,email}`; original success text/200; ADMIN only; no login credential provisioned. |
| `GET /users/all`, `GET /users/{id}` | Safe user DTOs; ADMIN only. |
| `PUT /users/{id}`, `DELETE /users/{id}` | Validated name/email update or safe deletion (204); ADMIN only. Booking history prevents deletion. |
| `POST /station/reg` | Existing station-create DTO; original success text/200; ADMIN only. |
| `GET /station/all`, `GET /station/{id}`, `GET /station/search?query=...` | Station DTOs; signed in. Public discovery uses the new `/api/stations` route. |
| `GET /station/isAvailable/{id}` | Existing availability text; 409 if full; signed in. |
| `PUT /station/reduceSlot/{id}` | Retained manual capacity reduction; ADMIN only. Not a physical charger integration. |
| `PUT /station/{id}`, `DELETE /station/{id}` | Station update DTO or safe deletion (204); ADMIN only. |
| `POST /booking/createBooking` | Existing `{userId,stationId,amount}` DTO and optional replay header; 201 booking DTO. Owner/ADMIN check; legacy positive `amount` field is validated but new stored amount is zero. Prefer the new browser endpoint. |
| `GET /booking/{id}`, `GET /booking/user/{userId}` | Owner or ADMIN reads; returns booking DTO(s). |
| `GET /booking/all`, `GET /booking/station/{stationId}` | ADMIN only. |
| `PUT /booking/cancel?id=...` | Owner/ADMIN; retains empty success response/200. |

Unknown JSON properties are rejected. Names/emails/station text have bounded lengths; total capacity must be positive; available capacity must be between zero and total. A station update cannot supply an available counter and overwrite occupied inventory. Email normalization and a database unique constraint protect account identity.

## 8. Database, configuration and dependency changes

### WEB-008: Versioned schema changes

The project keeps PostgreSQL. `V1__audited_backend_baseline.sql` defines the audited tables (`users`, `charging_stations`, `booking`), indexes, foreign keys, unique email/request key and named capacity/state checks. Fresh databases apply V1 and then V2.

`V2__accounts_and_local_reservations.sql` adds password/role/version fields, booking creation time, Completed state, zero-amount support and `user_notifications`. Its user foreign key deletes notices when an otherwise deletable account is deleted. Booking history continues to prevent deleting a user or station. No automatic cascade removes booking history.

The notification `(user_id,created_at)` index supports private inbox listing; existing booking user/station indexes remain. `created_at` is `TIMESTAMP WITH TIME ZONE`, mapped to Java `Instant`. Legacy bookings have no known original creation time, so their default backfill is migration time, not a reconstructed historical timestamp.

All runtime profiles use Flyway followed by `ddl-auto=validate`. `baseline-on-migrate=false` deliberately refuses to guess the state of an existing nonempty schema. `ops/DATABASE.md` explains backup/restore rehearsal, schema preflight, named-check normalization and explicit baseline version 1 before V2. Hibernate-generated original check names may differ; they must be reviewed rather than blindly dropped. No existing user/production database was accessed or overwritten by this work.

Flyway records applied versions and checksums in `flyway_schema_history`. An application restart validates this history instead of recreating the tables. Once a migration has been applied to a retained database, add a new version for the next change; editing the old SQL would cause checksum validation to fail. Hibernate validation then checks that the Java mappings agree with the migrated schema.

### WEB-009: Runtime and operational configuration

`application.properties` uses an explicit password environment variable, a dedicated local database default, bounded connection pool, five-second PostgreSQL lock timeout, safe error settings and narrow health exposure. Session settings prohibit URL session tracking. Development/local HTTP uses a loopback bind; `application-prod.properties` forces Secure cookies for HTTPS. The test profile runs the same Flyway SQL against isolated H2 in PostgreSQL mode and overrides PostgreSQL-only connection initialization.

Provider-setting comments now explicitly identify the `integration-demo` scope. The regular website profiles initiate neither online payments nor external messages; their operational notifications are the persisted inbox records described in Section 5.

`.env.example` supplies placeholder database/admin configuration only. Spring Boot does not automatically load it; Docker Compose does. `.gitignore` now excludes `.local/`, PID files and database backups in addition to existing secret/build ignores. This prevents private runtime credentials and data from entering the source history.

| Operational file | What it does |
| --- | --- |
| `ops/Start-LocalDatabase.ps1` | Creates only a new owned cluster, uses SCRAM and a random password in an ACL-restricted directory, binds loopback and preserves data between starts. Refuses an unmarked existing data directory or occupied port. |
| `ops/Stop-LocalDatabase.ps1` | Stops only the marked local cluster; never removes data. |
| `ops/Start-Website.ps1` | Builds/tests, reads private local credentials, optionally prompts for admin bootstrap, starts a hidden application process and checks HTTP health. Restores temporary database/server/bootstrap environment values after launch. |
| `ops/Stop-Website.ps1` | Checks saved PID/start time/process name before stopping the recorded Java process. |
| `ops/DATABASE.md` | Explains fresh setup, environment-based startup, explicit existing-data migration and deployment duties. |
| `Dockerfile` | Builds and runs verification with Java 17; final image runs the executable jar as a non-root user. |
| `compose.yaml` | Local app plus PostgreSQL, database health dependency, private credentials and a persistent volume. Only the application port is published, on host loopback. |
| `.dockerignore` | Keeps Git metadata, environment secrets, local data, build output and reports out of the image build context. |
| `README.md` | Provides the normal Windows workflow, first-use tasks, tests, Docker alternative and clear product boundaries. |

One Windows detail was corrected during review: `Start-Process -Wait` waits for a descendant process tree and can therefore wait forever after `pg_ctl` starts a server. The database script uses `Start-Process -PassThru` followed by `.WaitForExit()` to wait for `pg_ctl` itself. Servers are launched hidden as background helpers.

### WEB-010: Supported framework baseline, Java 17 retained

`pom.xml` moves from Spring Boot 3.5.3 to 4.1.1 while keeping Java/release 17. The earlier report identified dependency patching as remaining deployment work. The new website therefore uses a supported framework baseline, with the concrete dependency, import and test adjustments described below.

The new dependency set uses Boot's modular MVC/JPA/security/test starters, adds Spring Security and Flyway/PostgreSQL migrations, and keeps H2 test-only. Unused Lombok/DevTools dependencies are removed. Jackson mapper imports move to `tools.jackson.databind` where required by the new framework. Controller tests adopt the new Boot MVC test package and Spring's current Mockito override annotations. The application remains compiled for Java 17.

The original parent declared `<version>3.5.3</version>`; the current parent declares `<version>4.1.1</version>`. Framework compatibility must be judged by actual compilation and test results, recorded below. Official references: [Spring Boot support information](https://spring.io/projects/spring-boot#support), [system requirements](https://docs.spring.io/spring-boot/system-requirements.html) and [Spring Boot 4 migration guidance](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).

## 9. Learning walkthrough: follow a real request

### Register, sign in and refresh the browser

The browser's `api.js` first fetches `/api/auth/csrf`. Signup sends name/email/password and that CSRF header. `AuthController` validates the request; `AuthService` normalizes email and BCrypt-hashes the password; `UserRepository` inserts it. The controller returns a safe DTO with 201. The frontend then performs login as a separate request.

On login, Spring's authentication provider verifies BCrypt. The session strategy changes the session ID, reducing session-fixation risk. `AuthController` saves the authenticated security context; the cookie identifies this server-side session on later requests. The frontend discards the old CSRF cache, because authentication rotated the token. On browser refresh, `/api/auth/me` restores the signed-in screen from the real session.

### Reserve the final available unit

The browser sends `{stationId}` and one attempt UUID to `/api/bookings`. `SecurityConfig` requires login and CSRF. `SessionAccountFilter` verifies that the account/version is current. `WebsiteApiController` calls `CurrentUser.id()`; the client cannot select an arbitrary customer.

`BookingServiceImpl` begins the transaction, uses the existing lock protocol, checks replay identity and inventory, writes amount-zero booking, decrements the station and asks `InboxService` to store a notice. Commit makes all records visible together. The DTO becomes a confirmation dialog with a real booking ID. If another driver has taken the last unit, the transaction does not fabricate success; the browser explains the conflict and reloads capacity.

### Cancel or complete without manufacturing capacity

The owner may cancel; ADMIN may cancel or complete. Both operations lock the same station before loading the current booking state. The first valid terminal change increments availability once and writes one notice. A repeat of the same terminal change does nothing extra. A different terminal change conflicts. This combines pessimistic locking, transactional dirty checking and explicit state-transition rules.

Dirty checking means Hibernate remembers the values of an entity loaded inside the transaction. Changing its status or available counter marks it as changed; commit writes those changes together. Separate `save()` calls are unnecessary for those already-managed entities. This does not apply to an arbitrary detached Java object outside the transaction.

### Change a password and revoke old sessions

The authenticated request supplies current/new password. `AuthService` locks the account, validates the new password and verifies the existing hash. It stores the new hash and increments `credentialVersion`. The current session is invalidated; another browser with an older version is invalidated on its next request. This provides revocation without a distributed session registry, at the cost of one account lookup per authenticated request.

## 10. Testing and verification report

**Verified:** 126 Java tests passed on PostgreSQL and again on H2; 11 frontend tests passed; the packaged application retained verified records across restart. The observed browser checks and their limits are recorded below.

The integrated Java 17 / Spring Boot 4.1.1 **PostgreSQL** clean-test run completed on 17 October 2026 at 18:53:45 IST: **126 tests, zero failures, zero errors and zero skipped; BUILD SUCCESS** (`fullstack-test-postgres.log`, 1 minute 55 seconds). The integration classes used a fresh local PostgreSQL 17.11 database named `evcharging_test_20261017`; fixture cleanup never targeted the website database. Other tests in the same suite remain unit/controller tests and should not be described as 126 separate PostgreSQL integration tests.

An earlier H2 clean-test run passed 123 tests at 18:45:47 IST (`fullstack-test-second.log`). Three PostgreSQL-target guardrail tests were subsequently added, bringing the current suite to 126. A standalone H2 2.4.240 SQL runner also executed V1 and V2 successfully. All four operational PowerShell files parsed with zero syntax errors. These results do not substitute for full browser QA.

The final default-H2 `clean package` run completed at 18:57:39 IST: **126 tests, zero failures, zero errors and zero skipped; BUILD SUCCESS** (`fullstack-package-h2.log`, 3 minutes 34 seconds). Spring Boot repackaged `target/demo-0.0.1-SNAPSHOT.jar` as an executable archive containing its dependencies.

After two frontend review fixes, a resource-only `-DskipTests package` succeeded at 19:06:05 IST in 13.870 seconds. That step repackaged the updated JavaScript; it did not rerun or inflate the Java test counts. Package inspection confirmed the six static website assets and no H2, Mockito, JUnit or test libraries in runtime dependencies.

The frontend Node runner executed **11 tests: 11 passed, zero failed and zero skipped**. Eight exercise the actual API client using controlled network responses; three exercise status-aware reservation feedback, including terminal idempotent replays. JavaScript syntax checks also succeeded for `app.js`, `api.js` and `content.js`. These are separate from an actual browser completing the workflows.

The later blue/navy/coral revision also passed all 11 frontend tests and syntax checks for the three JavaScript modules. That revision changes CSS, SVG and browser metadata; the Java backend is unchanged. Its static assets are repackaged into the executable application separately from the full Java test runs above.

One theme repackaging attempt failed after 13.303 seconds at 19:41:51 IST because Windows held the running executable jar open and prevented renaming it to `demo-0.0.1-SNAPSHOT.jar.original` (`fullstack-theme-repackage.log`). This was an operational file-lock failure, rather than a Java compilation/test failure. The owned application process was stopped before rebuilding; PostgreSQL and its data directory were left running and unchanged. On Windows, stop this application's running jar before replacing or cleaning that jar, then restart it after a successful package.

The retry, `-DskipTests clean package`, **succeeded at 19:43:40 IST in 25.955 seconds** (`fullstack-theme-repackage-retry.log`). It compiled 64 main and 18 test source files for Java 17, then deliberately skipped test execution because the intervening changes were CSS/SVG/browser metadata and a configuration comment. The final themed executable jar is **59,916,868 bytes**, with no test-only runtime libraries. The earlier 126-test PostgreSQL/H2 results remain the actual Java test evidence; this packaging step adds no test executions.

Docker build/Compose was not executed because no Docker CLI was available on PATH. Public HTTPS deployment, migration of an existing user database, physical charger operation, real payment processing and external message delivery were not executed. Provider/hardware behavior cannot be inferred from these local tests.

The real website application connected to PostgreSQL 17.11, applied/validated both Flyway migrations, passed Hibernate mapping validation and started Tomcat on `http://127.0.0.1:18082/` at 18:52:58 IST. Its HTTP health/website request returned 200. A preexisting process occupied port 8081, so verification selected 18082 rather than stopping an unrelated process. The normal documented default remains 8081.

The **actual executable jar** then started with Java 17 / Boot 4.1.1 at 19:07:44 IST against the same PostgreSQL 17.11 database on port 55433, schema version 2 (`website-packaged-postgres.log`). The bundled Python runtime executed `work/http_persistence_check.py` before and after application restart. The canonical API SHA-256 fingerprint was identical: two accounts, one station and two bookings survived unchanged. Bootstrap environment variables were blank on restart; persistence did not rely on recreating users from bootstrap. This proves application-restart persistence for those verified records, not backup restoration or disaster recovery.

The final themed jar started at **19:44:16 IST**, using Java 17 / Boot 4.1.1, PostgreSQL 17.11 and schema version 2 (`website-themed-postgres.log`). Health returned UP. The canonical API persistence check passed again after this theme restart: the same two accounts, one station and two bookings matched the saved fingerprint. The database was retained across rebuilding the application's `target/` output.

### Actual browser checks

The real browser connected to the PostgreSQL-backed website on port 18082. The following actions were performed through the interface, rather than inferred from source or mocked responses:

| Browser workflow | Observed result |
| --- | --- |
| Administrator login, create station and edit it | Signed in; station created with capacity 3, then edited to capacity 4. |
| Customer list and logout | Real customer directory opened; sign-out returned to guest state. |
| Customer registration/login and station details | New account authenticated and real station details opened. |
| Reserve and view My bookings | Booking #1 was created with Confirmed status and shown in history. |
| Inbox and profile update | Mark read reduced unread count to zero; edited profile name appeared in the header. |
| Cancel booking #1 | Status became Cancelled and available capacity returned to 4. |
| Directory controls | Search for Riverside, available-only filter and Most available sort were used. |
| Second reservation and administrative completion | Booking #2 was created; after administrator login, All bookings/Complete changed it to Completed. Status filter showed zero active bookings. |
| Invalid then valid administrator login | Incorrect credentials produced a form error; correct credentials authenticated. |
| Repeated customer login and refresh | Wrong password was rejected inline; the correct password signed in. Browser refresh preserved the session, and My bookings showed the customer's two own Cancelled/Completed records. |
| Delete-station dialog | Confirmation opened and Keep station preserved the record. Final destructive confirmation was not clicked. |
| Privacy and Booking guide | Privacy displayed four authored sections and Booking guide displayed five. |
| Packaged application reload, Help and Refresh | Anonymous directory reloaded with capacity 4; Help displayed five sections; Help/Sign in accessibility labels were checked; Refresh completed an actual network reload. |
| Final blue/navy/coral theme | The repackaged Discover screen and login form loaded with the new palette. Customer login worked, and My bookings showed the same two terminal records; final screenshots were visually clean. |

Full-page screenshots, including an observed approximately 563-pixel-wide view and the final blue/coral screens, were visually reviewed and appeared clean. A dedicated device/viewport matrix was not performed. Password change was not submitted through the manual browser session, and final destructive station deletion was not confirmed. Password verification/session revocation, terminal replay semantics, deletion/history rules and access control have automated backend coverage; the manual observations above do not claim that every possible browser/interleaving/responsive state was exercised. The responsive layouts and handlers are implemented, with these verification limits stated explicitly.

The first integrated Maven test attempt ran 123 tests and reported one failure/four errors. Those failures were in test fixtures affected by transaction-proxy spying and MockMvc's unexecuted static-page forward. The fixtures were corrected: Mockito stubs target the spy behind the transaction proxy, and the home-page test separately checks the recorded forward and actual static HTML. The same total of 123 tests then passed; tests were not deleted to manufacture a green result. The three later database-target guardrail tests are included in both final 126-test runs.

### Tests created or updated

| Test file | What it verifies |
| --- | --- |
| `auth/AuthSecurityIntegrationTest.java` | Real security filter chain: CSRF, hash-only storage, duplicate/role injection rejection, session/token rotation, logout, role separation and multi-session password revocation. |
| `auth/AuthenticationAttemptLimiterTest.java` | Address/window isolation, expiry and BCrypt ASCII/Unicode byte-limit behavior. |
| `auth/AdminBootstrapTest.java` | No default admin; explicit normalized/hashed provisioning; existing account not promoted or reset. |
| `security/CurrentUserTest.java` | Own/other-account rules, ADMIN access, absent identity and safe credential erasure. |
| `WebsiteApiIntegrationTest.java` | Public assets/directory, authenticated ownership, real reservation DTOs, zero charge, idempotency, notices, station administration and protected profile updates. |
| `BookingIntegrationTest.java` | Immediate lifecycle, completion, counter/inbox rollback, isolation before commit, last-unit and multi-unit races, lock waiting, cancellation/completion race, replay and safe history/capacity behavior. |
| `PostgresTestSupport.java` and `PostgresTestSupportTest.java` | Optional real PostgreSQL execution for integration tests. The helper accepts only `127.0.0.1` and a database named `evcharging_test_<suffix>`, rejects URL options/remote/application targets, and requires explicit credentials. Three unit tests verify those guardrails. |
| `DemoApplicationTests.java` | Retains the application-context startup test and adopts the optional isolated PostgreSQL helper. |
| Controller tests | Existing endpoint/status/validation checks remain; Boot 4 imports and mocked trusted identity are updated where required. These isolated MVC tests do not alone prove the real filter chain. |
| `src/test/frontend/api.test.mjs` | Eight Node tests against the actual browser HTTP module: CSRF rotation, cookies, replay-header forwarding, errors, 204 and session-expiry behavior. |
| `src/test/frontend/reservation-feedback.test.mjs` | Three regression tests for confirmed/cancelled/completed feedback; a terminal replay must not claim capacity is still reserved. |

Expected developer commands, to rerun from the repository with JDK 17:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd package
node --test src/test/frontend/*.test.mjs
node --check src/main/resources/static/assets/app.js
node --check src/main/resources/static/assets/api.js
node --check src/main/resources/static/assets/content.js
```

The executed Maven runs used the bundled JDK 17, workspace Maven cache and writable temporary directory. The command templates below retain the actual batch/no-transfer flags, goals and named `Tee-Object` logs; only the common workspace directory is parameterized. Credentials were read privately into environment variables. No additional JVM or test-fork flags were used.

```powershell
# From outputs/EV-Charging-Backend in this workspace:
$workspace = (Resolve-Path '..\..').Path
$env:JAVA_HOME = "$workspace\work\runtime\jdk17\jdk-17.0.20.1+1"
$env:MAVEN_USER_HOME = "$workspace\work\maven"
$env:TEMP = "$workspace\work\tmp"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$privateTestDatabase = Get-Content -Raw `
    -LiteralPath "$workspace\work\postgres-root\credentials.json" | ConvertFrom-Json
$env:EV_TEST_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:55433/evcharging_test_20261017'
$env:EV_TEST_POSTGRES_USERNAME = $privateTestDatabase.username
$env:EV_TEST_POSTGRES_PASSWORD = $privateTestDatabase.password
.\mvnw.cmd --batch-mode --no-transfer-progress `
    "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" clean test 2>&1 |
    Tee-Object -FilePath "$workspace\work\fullstack-test-postgres.log"
# Remove the explicit PostgreSQL test overrides for the default H2 build.
Remove-Item Env:EV_TEST_POSTGRES_URL, Env:EV_TEST_POSTGRES_USERNAME, `
    Env:EV_TEST_POSTGRES_PASSWORD -ErrorAction SilentlyContinue
.\mvnw.cmd --batch-mode --no-transfer-progress `
    "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" clean package 2>&1 |
    Tee-Object -FilePath "$workspace\work\fullstack-package-h2.log"
# After frontend-only fixes, rebuild resources/jar without repeating Java tests:
.\mvnw.cmd --batch-mode --no-transfer-progress `
    "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" -DskipTests package 2>&1 |
    Tee-Object -FilePath "$workspace\work\fullstack-final-repackage.log"
# Final theme retry, after stopping the owned running Java application:
.\mvnw.cmd --batch-mode --no-transfer-progress `
    "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" -DskipTests clean package 2>&1 |
    Tee-Object -FilePath "$workspace\work\fullstack-theme-repackage-retry.log"
```

The final frontend verification explicitly ran `node --test src/test/frontend/api.test.mjs src/test/frontend/reservation-feedback.test.mjs` and recorded 11 passing tests in `frontend-final-tests.log`. The PostgreSQL and H2 runs each execute the same current 126-test Java suite; they are two database configurations, not 252 distinct test cases.

Tests use isolated data, not a user's existing PostgreSQL database. H2 PostgreSQL mode is useful but is not evidence that PostgreSQL-specific behavior passed. Docker execution, live providers, actual charger hardware and public TLS hosting require separate evidence and must not be inferred from a successful Java build.

To opt into the PostgreSQL integration path, explicitly create a new disposable local database named `evcharging_test_<suffix>` and set `EV_TEST_POSTGRES_URL`, `EV_TEST_POSTGRES_USERNAME` and `EV_TEST_POSTGRES_PASSWORD` for the test process. These tests delete their fixture data; never point them at an application database. The helper's restrictive URL validation is an additional safeguard, not a substitute for deliberately selecting an isolated database.

## 11. How to run and verify the website yourself

### Fresh Windows setup

Install a supported JDK 17 and PostgreSQL binaries. The [official PostgreSQL Windows page](https://www.postgresql.org/download/windows/) provides the installer and links to portable binary downloads. Maven is supplied through the repository wrapper; the first build needs internet access for dependencies.

```powershell
# From the repository; use your real installation directories.
.\ops\Start-LocalDatabase.ps1 -PostgresBin 'C:\path\to\pgsql\bin'
.\ops\Start-Website.ps1 -JdkHome 'C:\path\to\jdk-17'
```

Enter your administrator email and a unique password when prompted on first startup. Open `http://127.0.0.1:8081/`. The normal script creates a dedicated database under `.local/postgres`, using port 55432. Data survives application/database restarts. Keep using the same browser host so its session cookie remains consistent.

If the website port is occupied, choose a free one with `-Port 18082` on `Start-Website.ps1` and open the matching URL. Do not stop an unrelated process to reclaim a port. Integrated verification on this machine used port 18082 for that reason.

For the currently running instance on this machine, `LOCAL_WEBSITE_ACCESS.md` beside the PDF explains access and the private, ignored `.local/admin-access.json` administrator credential file. Its values are excluded from this report and the source archive. Fresh installations create their own administrator using the setup prompt or documented environment variables.

Sign in as the administrator and create the real station records. Then sign out, register a customer, search, reserve, inspect My bookings/inbox and cancel. Sign in as administrator again to inspect all reservations and complete a confirmed one. The initial empty station screen is intentional: the application does not invent a live charging network.

Stop with `ops/Stop-Website.ps1` and then `ops/Stop-LocalDatabase.ps1`. Logs/private metadata remain in `.local/`. Do not delete a database directory or Docker volume as a troubleshooting shortcut. If a startup step fails, inspect the relevant log and correct the reported cause.

For an explicitly selected external PostgreSQL database, set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SPRING_PROFILES_ACTIVE` and optional bootstrap variables in the process environment; follow `ops/DATABASE.md`. `.env.example` contains no real secrets and is not automatically loaded by Spring Boot. Do not point a new version at historical data without the documented migration rehearsal.

### Local Docker alternative

Copy `.env.example` to private `.env`, replace database/admin placeholders, and use Docker Desktop with Linux containers:

```powershell
docker compose up --build -d
docker compose logs --tail 100 app
docker compose stop
```

The Compose setup is local HTTP and publishes only loopback. `prod` forces Secure session cookies and therefore needs HTTPS at a trusted reverse proxy; plain HTTP login will not work with that production cookie. Docker usage is verified only if explicitly stated in Section 10.

## 12. Security review and remaining work

Implemented controls include BCrypt, no plaintext credentials, safe DTOs, server-side identity, owner/ADMIN checks on old and new routes, CSRF, session-ID rotation, cookie-only tracking, old-session revocation, authentication throttling, safe JSON errors, same-origin CSP, unknown-field rejection, database constraints and explicit migration ownership.

The earlier committed database credential remains a manual remediation item: remove/rotate it at its provider and perform controlled Git-history cleanup if still applicable. Removing a value from current source does not invalidate its old copies. No secret value belongs in this report, source ZIP or public logs.

| Priority | Remaining work and reason |
| --- | --- |
| Required before a lasting customer service | Free-demo hosting/TLS is now deployed; reliable hosting, tested backup/restore, monitoring/alerts, operator contact/privacy/retention policy and an administrator recovery procedure remain required for lasting operation. |
| Required for historical data | Rehearse backup restoration, compare the actual old schema, normalize the reviewed booking check name and explicitly baseline before V2. Provision legacy accounts through an authorized process. |
| Required product decision | Define stale-reservation/no-show policy. Approved current-capacity bookings hold inventory until cancellation or administrator completion; no automatic timeout was invented. |
| Recommended | Add account email verification and a secure password-recovery flow; agree audit logging and data retention before opening customer registration publicly. |
| Recommended for scale | Pagination for unbounded station/admin booking/customer lists, shared/edge rate limiting, trusted proxy handling and a deliberate multi-instance session strategy. |
| Optional product expansion | Future time slots/connectors, hardware charging protocols, metering, real payment/verification/refund records and durable external provider delivery. Each requires business rules and provider configuration. |

The application intentionally lacks checkout, forgot-password email, role-management and hardware-start buttons. Introducing a visible control before its real backend behavior exists would mislead users. Existing visible controls are connected to the agreed implemented scope; deployment/provider prerequisites are stated plainly.

## 13. File inventory against the real Git comparison

The inventory below is generated from tracked changes relative to `a6c1923` plus new, nonignored files. It covers source, configuration, tests, operational instructions and this report. Generated build output, runtime databases, downloaded toolchains, credentials and verification scratch files are excluded. No intentional source file deletion is part of this phase.

<!-- FILE_INVENTORY_START -->
**Actual inventory: 93 files; 69 created; 24 modified; 0 deleted.**

| Exact relative file path | Action | Reason | Change |
| --- | --- | --- | --- |
| `.dockerignore` | Created | Exclude secrets/local data from image context. | WEB-009 |
| `.env.example` | Modified | Placeholder database/admin template, default-false demo mode and separate Render database guidance. | WEB-009/012/013 |
| `.github/workflows/verify.yml` | Created | Java 17 H2/PostgreSQL verification and Node frontend checks before manual deployment. | WEB-013 |
| `.gitignore` | Modified | Exclude local data, private runtime files and backups. | WEB-009 |
| `Dockerfile` | Created | Verified-build non-root Java 17 image with heap headroom for the free demo instance. | WEB-009/013 |
| `README.md` | Created | Windows first use, current PC-hosted Free ngrok setup, historical Render scope and verification limits. | WEB-009/013/015 |
| `compose.yaml` | Created | Local durable PostgreSQL and application deployment. | WEB-009 |
| `docs/PC_HOSTED_PUBLIC_LINK_GUIDE.md` | Created | Current PC-hosted Free assigned-domain setup, private account handoff, security, limits and verification. | WEB-015 |
| `docs/PUBLIC_DEMO_DEPLOYMENT_GUIDE.md` | Created | Free public-demo setup, secrets, verification, database separation and expiry limits. | WEB-013 |
| `docs/WEBSITE_COMPLETION_CHANGE_REPORT.md` | Created | Full class/file/content report including historical cloud rollout and current WEB-015 PC-hosted choice. | All |
| `ops/DATABASE.md` | Created | Operational setup/stop or safe migration instructions; see Section 8. | WEB-009 |
| `ops/Start-LocalDatabase.ps1` | Created | Operational setup/stop or safe migration instructions; see Section 8. | WEB-009 |
| `ops/Start-PublicTunnel.ps1` | Created | Validated foreground loopback HTTP tunnel, local health precheck and disabled inspection; no token storage. | WEB-015 |
| `ops/Start-Website.ps1` | Created | Operational setup/stop or safe migration instructions; see Section 8. | WEB-009 |
| `ops/Stop-LocalDatabase.ps1` | Created | Operational setup/stop or safe migration instructions; see Section 8. | WEB-009 |
| `ops/Stop-Website.ps1` | Created | Operational setup/stop or safe migration instructions; see Section 8. | WEB-009 |
| `pom.xml` | Modified | Boot 4.1.1 modular starters, security and migrations; Java 17 retained. | WEB-010 |
| `render.yaml` | Created | Explicit free single-instance web service and separate fresh PostgreSQL demo database. | WEB-013 |
| `src/main/java/com/evcharging/auth/AdminBootstrap.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/auth/AuthController.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/auth/AuthExceptionHandler.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/auth/AuthService.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/auth/AuthUserDTO.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/auth/AuthenticationAttemptLimiter.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/auth/ChangePasswordRequest.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/auth/LoginRequest.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/auth/PasswordPolicy.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/auth/RegisterRequest.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/auth/TooManyAttemptsException.java` | Created | New authentication class; class-specific behavior in Section 3. | WEB-002 |
| `src/main/java/com/evcharging/config/AppConfig.java` | Modified | Provider examples restricted to integration-demo profile. | WEB-005 |
| `src/main/java/com/evcharging/controller/BookingController.java` | Modified | Ownership/admin checks on retained booking routes. | WEB-003 |
| `src/main/java/com/evcharging/controller/SiteInfoController.java` | Created | Public two-field visitor information with default-false demo mode and no deployment secrets. | WEB-012 |
| `src/main/java/com/evcharging/controller/WebsiteApiController.java` | Created | Website routes, trusted customer identity and safe DTOs. | WEB-007 |
| `src/main/java/com/evcharging/dto/BookingResponseDTO.java` | Modified | Station/location/time fields for useful booking cards. | WEB-004 |
| `src/main/java/com/evcharging/entity/Booking.java` | Modified | Creation timestamp, Completed state and zero-amount check. | WEB-004 |
| `src/main/java/com/evcharging/entity/User.java` | Modified | Hashed credentials, role and session-revocation version. | WEB-001 |
| `src/main/java/com/evcharging/entity/UserNotification.java` | Created | Durable private notice entity with read state. | WEB-005 |
| `src/main/java/com/evcharging/global/exception/handler/GlobalExceptionHandler.java` | Modified | Safe JSON 401/403 for security exceptions. | WEB-003 |
| `src/main/java/com/evcharging/notification/BookingNotificationListener.java` | Modified | Old mock-event listener isolated from website runtime. | WEB-005 |
| `src/main/java/com/evcharging/repository/UserNotificationRepository.java` | Created | Bounded owned listing and owned record lookup. | WEB-005 |
| `src/main/java/com/evcharging/repository/UserRepository.java` | Modified | Case-insensitive account lookup for real login. | WEB-001 |
| `src/main/java/com/evcharging/security/AccountUserDetailsService.java` | Created | New identity/security class; class-specific behavior in Section 3. | WEB-003 |
| `src/main/java/com/evcharging/security/CurrentUser.java` | Created | New identity/security class; class-specific behavior in Section 3. | WEB-003 |
| `src/main/java/com/evcharging/security/SecurityConfig.java` | Created | Owner/admin access control, public site information and admission before CSRF session allocation. | WEB-003/011/012 |
| `src/main/java/com/evcharging/security/SessionAccountFilter.java` | Created | New identity/security class; class-specific behavior in Section 3. | WEB-003 |
| `src/main/java/com/evcharging/security/SessionAdmissionFilter.java` | Created | Guards all fresh-session allocation before CSRF and returns safe quota/capacity errors. | WEB-011 |
| `src/main/java/com/evcharging/security/SessionAdmissionLimiter.java` | Created | Bounded per-client admission of fresh sessions while preserving existing browsers. | WEB-011 |
| `src/main/java/com/evcharging/security/SessionCapacityConfig.java` | Created | Container session cap with shared synchronization for creation and session-ID rotation. | WEB-011 |
| `src/main/java/com/evcharging/security/SessionLimitException.java` | Created | Public-safe 429/503 session-allocation failures with retry guidance. | WEB-011 |
| `src/main/java/com/evcharging/security/SessionLimitExceptionHandler.java` | Created | Consistent session-capacity JSON errors and Retry-After headers. | WEB-011 |
| `src/main/java/com/evcharging/security/UserPrincipal.java` | Created | New identity/security class; class-specific behavior in Section 3. | WEB-003 |
| `src/main/java/com/evcharging/service/BookingService.java` | Modified | Administrator-completion service contract. | WEB-004 |
| `src/main/java/com/evcharging/service/InboxService.java` | Created | Transactional inbox writes and owned read updates. | WEB-005 |
| `src/main/java/com/evcharging/service/impl/BookingServiceImpl.java` | Modified | Zero-charge reserve-now lifecycle, completion and atomic inbox. | WEB-004 |
| `src/main/java/com/evcharging/service/impl/NotificationServiceImpl.java` | Modified | Mock notification strategy isolated to demonstration profile. | WEB-005 |
| `src/main/java/com/evcharging/service/impl/PaymentServiceImpl.java` | Modified | Mock payment strategy isolated to demonstration profile. | WEB-005 |
| `src/main/resources/application-dev.properties` | Modified | Explicit environment/profile, migration and secure-session settings. | WEB-009 |
| `src/main/resources/application-local.properties` | Created | Explicit environment/profile, migration and secure-session settings. | WEB-009 |
| `src/main/resources/application-prod.properties` | Modified | Validated schema, Secure cookies and bounded graceful shutdown. | WEB-009/013 |
| `src/main/resources/application-render.properties` | Created | Separate managed JDBC database, trusted proxy boundary and free-instance resource bounds. | WEB-011/013 |
| `src/main/resources/application-tunnel.properties` | Created | Loopback-only trusted ngrok forwarding with Secure cookies and no datasource/admin/demo override. | WEB-015 |
| `src/main/resources/application.properties` | Modified | Database/security defaults, ignored direct forwarding headers and host-port fallback. | WEB-009/013 |
| `src/main/resources/db/migration/V1__audited_backend_baseline.sql` | Created | Versioned audited schema/accounts/inbox SQL; see Section 8. | WEB-008 |
| `src/main/resources/db/migration/V2__accounts_and_local_reservations.sql` | Created | Versioned audited schema/accounts/inbox SQL; see Section 8. | WEB-008 |
| `src/main/resources/static/assets/api.js` | Created | Cookies/CSRF/error HTTP boundary with optional short timeout for noncritical site information. | WEB-006/012 |
| `src/main/resources/static/assets/app.js` | Created | Real API-backed screens/actions and bounded runtime demo information before signup/reservation. | WEB-006/012 |
| `src/main/resources/static/assets/content.js` | Created | Help/privacy/booking content, terminal-reservation feedback and honest public-demo explanations. | WEB-006/012 |
| `src/main/resources/static/assets/favicon.svg` | Created | Local navy/coral website browser icon. | WEB-006 |
| `src/main/resources/static/assets/styles.css` | Created | Responsive blue/navy/coral screens, reduced motion and readable public-demo notices. | WEB-006/012 |
| `src/main/resources/static/index.html` | Created | Accessible shell/navigation/dialog hooks, navy theme and hidden-by-default demo notice. | WEB-006/012 |
| `src/test/frontend/README.md` | Created | Explicit three-file command, all 17 frontend cases and evidence limits. | WEB-006/012 |
| `src/test/frontend/api.test.mjs` | Created | Nine real API-client tests including the short optional site-information timeout. | WEB-006/012 |
| `src/test/frontend/public-demo.test.mjs` | Created | Five tests for strict demo mode, honest limitations and unchanged normal-mode content. | WEB-012 |
| `src/test/frontend/reservation-feedback.test.mjs` | Created | Three regressions preventing active-capacity claims for terminal replays. | WEB-006 |
| `src/test/java/com/evcharging/BookingIntegrationTest.java` | Modified | Completion, inbox atomicity/isolation and concurrency regression tests. | WEB-004/005 |
| `src/test/java/com/evcharging/DemoApplicationTests.java` | Modified | Retains context startup test; allows explicit isolated PostgreSQL verification. | WEB-008/010 |
| `src/test/java/com/evcharging/PostgresTestSupport.java` | Created | Opt-in PostgreSQL test connection restricted to loopback/test database names. | WEB-008 |
| `src/test/java/com/evcharging/PostgresTestSupportTest.java` | Created | Rejects unsafe remote/application database test targets and URL options. | WEB-008 |
| `src/test/java/com/evcharging/WebsiteApiIntegrationTest.java` | Created | Real website API ownership, administration and input tests. | WEB-007 |
| `src/test/java/com/evcharging/auth/AdminBootstrapTest.java` | Created | Authentication/provisioning/limiting verification; see Section 10. | WEB-002 |
| `src/test/java/com/evcharging/auth/AuthSecurityIntegrationTest.java` | Created | Authentication/ownership and anonymous session-free public site-information integration tests. | WEB-002/011/012 |
| `src/test/java/com/evcharging/auth/AuthenticationAttemptLimiterTest.java` | Created | Authentication/provisioning/limiting verification; see Section 10. | WEB-002 |
| `src/test/java/com/evcharging/controller/BookingControllerTest.java` | Modified | Retain controller contract tests; adapt Boot 4/identity test fixtures. | WEB-007/010 |
| `src/test/java/com/evcharging/controller/ChargingStationControllerTest.java` | Modified | Retain controller contract tests; adapt Boot 4/identity test fixtures. | WEB-007/010 |
| `src/test/java/com/evcharging/controller/SiteInfoControllerTest.java` | Created | Normal/demo visitor-information contracts, safe fields and no-store response tests. | WEB-012 |
| `src/test/java/com/evcharging/controller/UserControllerTest.java` | Modified | Retain controller contract tests; adapt Boot 4/identity test fixtures. | WEB-007/010 |
| `src/test/java/com/evcharging/security/CurrentUserTest.java` | Created | Trusted identity and ownership unit tests; see Section 10. | WEB-003 |
| `src/test/java/com/evcharging/security/RenderProxyBoundaryTest.java` | Created | Real Tomcat proxy-boundary behavior using the committed Render profile. | WEB-011/013 |
| `src/test/java/com/evcharging/security/SessionAdmissionFilterTest.java` | Created | Fresh-session quotas, reuse, expiry, bounded clients and rejected-CSRF allocation coverage. | WEB-011 |
| `src/test/java/com/evcharging/security/SessionCapacityHttpIntegrationTest.java` | Created | Actual embedded Tomcat safe capacity failure, existing-session reuse and released capacity. | WEB-011 |
| `src/test/java/com/evcharging/security/SessionCapacityTest.java` | Created | Concurrent creation, expiry and session-ID rotation cap regressions. | WEB-011 |
| `src/test/java/com/evcharging/security/TunnelProxyBoundaryTest.java` | Created | Eight actual Tomcat/profile regressions for tunnel trust, protocol handling and unchanged database/local settings. | WEB-015 |
| `src/test/resources/application-test.properties` | Modified | Isolated H2 with real migrations/validation and no admin credentials. | WEB-008/009 |
<!-- FILE_INVENTORY_END -->

## 14. Completion checklist

This checklist records the original local release. Sections 15-19 document the later cloud-demo work; Section 20 records the current PC-hosted choice and verification boundary.

| Deliverable | State |
| --- | --- |
| Real same-origin website and content files | Implemented from scratch; exact files and actions described above. |
| Password login, sessions, roles and ownership | Implemented with server enforcement and dedicated tests. |
| Immediate booking/cancel/complete lifecycle | Implemented; zero online amount; shared transactional station lock. |
| Real private in-app notifications | Persisted transactionally with bookings; owned read state. |
| PostgreSQL migrations and local/deployment instructions | Implemented; no automatic migration of existing user data. |
| Final test/browser/PostgreSQL results | PostgreSQL and H2: 126 tests passed in each configuration; 11 frontend tests passed; actual browser and packaged-jar restart persistence checks recorded. |
| Readable class/file/content change documentation | This report; a PDF is generated only after final evidence is inserted and layout is checked. |
| Public hosting, online charges, physical charger control | This checklist predates the historical Render rollout. Current public access is the PC-hosted ngrok approach in Section 20, pending actual tunnel verification. Online charges and physical charger control remain outside scope. |

The next useful milestone is to rehearse production deployment with your real station records, TLS, backup/restore and recovery procedures, then choose the next explicit product capability. Scheduled charging and online payments should follow those decisions rather than be implied by a button or a mock provider class.

## 15. Public-demo addendum — WEB-011: session admission and cloud security

**Later scope decision, 18 October 2026.** After the local website was completed, the user approved preparation of a free public demonstration using a separate, fresh hosted database. This addendum records that later work; Sections 1–14 remain the historical local-release report. Public-demo changes below are compared with actual commit `3aa160a`, rather than the earlier backend comparison point. Preparing deployment files does not prove that a public deployment has completed. Deployment configuration and its eventual verification are documented separately under WEB-013.

The public demonstration still reserves application capacity immediately, collects no money and activates no physical equipment. No new booking limit, expiry rule, future appointment or payment workflow was invented. The existing local `EVCHARGING` database is not read, copied, baselined or changed by these security/UI additions. Its preservation and any future migration remain separate requirements.

### The verified gap: protecting a password endpoint is not enough

Before this addendum, `AuthenticationAttemptLimiter` already limited login, registration and password-change attempts. Those checks run inside `AuthController`. Spring's CSRF filter runs earlier and can create an anonymous HTTP session while preparing a CSRF token, even when it subsequently rejects a POST for a missing token. Public `GET /api/auth/csrf` also creates a session when a new browser asks for its token.

An attacker repeatedly discarding cookies could therefore consume session memory without reaching the controller limiter. Existing 30-minute inactivity expiry limits session lifetime, but does not limit how many can be alive simultaneously. This matters on a small free host. The fix has two layers: limit fresh session admissions per client, then enforce a total active-session cap. Existing browser sessions remain usable at either limit.

### Actual filter-chain change

**Modified file/class:** `src/main/java/com/evcharging/security/SecurityConfig.java`, `applicationSecurity`.

**Before, from `3aa160a`:**

```java
.addFilterAfter(new SessionAccountFilter(users), SecurityContextHolderFilter.class);
```

**After:**

```java
.addFilterAfter(new SessionAccountFilter(users), SecurityContextHolderFilter.class)
.addFilterBefore(new SessionAdmissionFilter(admissions, mapper), CsrfFilter.class);
```

The method now receives the `SessionAdmissionLimiter` bean as a factory-method parameter supplied by Spring. The admission filter is constructed only inside the security chain; it is not separately registered as a servlet filter and therefore does not run twice. Existing account/version checks, authentication, CSRF, ownership, role checks and session-ID rotation remain active. The same change adds anonymous GET access to the intentionally safe `/api/site-info` endpoint described in WEB-012.

### Every new security class and what it does

All five files in the following table are **new** under `src/main/java/com/evcharging/security/`; none existed at `3aa160a`. There is no fabricated earlier implementation for these files.

| Class / method | What changed and why | How it works / practical effect |
| --- | --- | --- |
| `SessionAdmissionLimiter.admit` | Adds a separate quota for creating browser sessions, rather than replacing credential-attempt limits. | A synchronized map stores a count and expiry per container-reported remote address. Default: 30 admissions per 300 seconds, at most 10,000 client buckets. Expired buckets are removed. A full map rejects new buckets instead of growing indefinitely or silently allowing requests. Configuration must be positive. |
| `SessionAdmissionFilter.doFilterInternal` and request-wrapper `getSession` | Checks the moment a session would actually be created, before CSRF can allocate it. | The wrapper first calls `getSession(false)`. If a session already exists, it returns it without consuming a fresh-session quota. Otherwise, a requested creation must pass admission before delegating to the container. Repeated `getSession` calls in the same request count once. This avoids relying on a fragile list of URL spellings and also covers encoded paths and missing-token POSTs. |
| `SessionLimitException` | Introduces public-safe, typed allocation failures. | Carries only HTTP status, stable error code, user-facing message and retry delay. Per-client admission returns 429 `SESSION_ADMISSION_LIMIT`; total container capacity returns 503 `SESSION_CAPACITY_REACHED`. Neither response reveals an IP, stack trace or Tomcat exception. |
| `SessionLimitExceptionHandler.handle` | Handles allocation failures raised while MVC resolves a controller's lazy CSRF token. | A high-priority controller advice returns the existing `ApiError` DTO and a `Retry-After` header. This is necessary because the general unexpected-error handler would otherwise turn that failure into a 500. The filter produces the same response shape for failures occurring before MVC. |
| `SessionCapacityConfig.boundedSessions` and `BoundedSessionManager` | Adds an actual Tomcat session-manager cap, including concurrency protection. | A web-server customizer installs a `StandardManager` subclass with a positive configured cap. New-session creation and session-ID remapping share one monitor. The manager keeps the existing nonpersistent, in-memory session model. Expiring or invalidating a session releases its place; restart clears sessions. |

The central wrapper logic is deliberately small:

```java
HttpSession existing = super.getSession(false);
if (existing != null || !create) { return existing; }
if (!admitted) {
    limiter.admit(getRemoteAddr());
    admitted = true;
}
```

This is the **decorator pattern** applied to `HttpServletRequest`: normal servlet behavior is delegated, with one check added before allocation. It protects the real allocation operation instead of assuming that only a particular controller creates sessions. It does not create a session merely to inspect whether one exists.

### Why the container cap also needs synchronization

Tomcat's default session manager checks the active count before inserting a new session. Without a shared lock, concurrent requests could both observe one free place. The new manager serializes that check-and-insert operation:

```java
@Override
public synchronized Session createSession(String requestedId) {
    return super.createSession(requestedId);
}
```

A subsequent review identified a second, subtler race. Login rotates the existing session ID. Tomcat temporarily removes the old map entry and then inserts the same session under its new ID. If creation alone were synchronized, another thread could mistake that temporary absence for a free place. The final implementation protects the shared remapping method too:

```java
@Override
protected synchronized void changeSessionId(Session session, String newId,
        boolean notifySessionListeners, boolean notifyContainerListeners) {
    super.changeSessionId(session, newId, notifySessionListeners, notifyContainerListeners);
}
```

Both `rotateSessionId` and public `changeSessionId` use this protected method. Java's monitor is reentrant, so the normal calls inside Tomcat continue to work. A concurrent creation waits until the complete ID change finishes, then sees the correct active count. This is the same engineering lesson as booking capacity protection: the invariant must cover every operation that can temporarily change the count, not just the obvious insert.

There was no custom manager at `3aa160a`; the first new version synchronized creation only. Review corrected the rotation race before treating the new strict-cap behavior as complete. A deterministic regression pauses real session-ID rotation between removal and insertion and checks that another admission cannot pass through that temporary gap. This repairs the cap guarantee; it does not change login's session-fixation protection.

### Limits, errors and trade-offs

| Setting / response | Current behavior |
| --- | --- |
| `app.security.session-admission.max-per-window` | Default 30 new sessions per client window. |
| `app.security.session-admission.window-seconds` | Default 300 seconds. |
| `app.security.session-admission.max-clients` | Default maximum of 10,000 tracked client buckets. |
| `app.security.max-active-sessions` | Default 1,000; the Render profile selects 512. |
| Client admission limit reached | 429, `SESSION_ADMISSION_LIMIT`, appropriate `Retry-After`; no new session allocated. |
| Total session capacity reached | 503, `SESSION_CAPACITY_REACHED`, `Retry-After: 60`; no new session cookie issued. |
| Existing valid session | Reused normally, including while the server is at capacity. |

Responses retain `timestamp`, `status`, `code`, `message`, `path` and `fieldErrors`. A user can retry after the indicated delay or continue using their existing browser session. The application does not loop or automatically replay a state-changing request.

These limits are process-local safeguards for one instance. People sharing one public IP share its limits; restart clears counters and sessions. The existing authentication limiter still independently protects password hashing: ten login attempts per five minutes, five registrations per hour and five password-change attempts per five minutes. A session cap bounds one source of memory consumption; it is not proof against every denial-of-service attack or evidence of measured free-host memory headroom. Multiple replicas would need a deliberate session/rate-limit design.

### The proxy boundary: use the actual client, not an arbitrary header

`AuthController` and `SessionAdmissionLimiter` intentionally use the servlet container's `getRemoteAddr()`. They do not parse `X-Forwarded-For` themselves. Behind a reverse proxy, an unconfigured address can identify the proxy rather than the customer, incorrectly sharing a login limit across many customers. Conversely, blindly trusting a caller's first forwarded value would let the caller choose another quota bucket.

The base configuration now explicitly disables forwarding. Only the Render profile enables Tomcat's native `RemoteIpValve`, with private/loopback internal-hop CIDRs and the forwarded client/protocol headers. Additional public trusted proxies and forwarded host/port overrides remain disabled. The valve traverses the chain from the right and stops at the nearest untrusted hop. It does not simply accept an attacker-supplied leftmost value. Deployment-specific property and TLS details belong to WEB-013.

**Verification boundary:** `RenderProxyBoundaryTest` exercises the actual Tomcat valve using the checked-in Render properties. It can establish what that configuration does for synthetic private/public peers. It cannot establish which direct peer and forwarded chain Render actually supplies to this deployment. Live HTTPS, cookie handling, proxy-hop behavior and independent real-client limits must be verified after provisioning. If the actual peer is outside the approved trust boundary, diagnose it; do not change the configuration to trust every address merely to make a test pass.

### WEB-011 verification: final Java tests executed

The earlier 126-test results remain historical evidence for the previous release. They must not be presented as execution of the following new tests.

| Test file under `src/test/java/com/evcharging/` | Added coverage |
| --- | --- |
| `security/SessionAdmissionFilterTest.java` — five tests | New clients limited before allocation; separate client addresses; existing-session reuse; a real CSRF-filter missing-token POST; expiry; spoofed forwarding headers ignored by application code; bounded client map. |
| `security/SessionCapacityTest.java` — two tests | Eight simultaneous creations respect a cap of two; expiry releases capacity; login rotation cannot temporarily admit another session beyond the cap. |
| `security/RenderProxyBoundaryTest.java` — four tests | Actual valve, actual profile properties, distinct clients behind a private proxy, public peer spoof rejection, rightmost untrusted-hop selection, and base forwarding disabled. |
| `security/SessionCapacityHttpIntegrationTest.java` — one test | Real embedded Tomcat HTTP path: two sessions accepted, third safely receives 503/no cookie, an existing cookie still works, expiry permits replacement. Uses the guarded optional PostgreSQL test support. |
| `auth/AuthSecurityIntegrationTest.java` — existing test extended | Anonymous site-info access succeeds, its demo property is a boolean, and that safe public request creates no session. |

Twelve security tests were added; no test was removed. Proposed targeted command, **not an executed-result claim**:

```powershell
.\mvnw.cmd '-Dtest=SessionAdmissionFilterTest,SessionCapacityTest,RenderProxyBoundaryTest,SessionCapacityHttpIntegrationTest,AuthSecurityIntegrationTest' test
```

**Executed final verification:** H2 `clean verify` and isolated PostgreSQL `test` each passed all 140 Java tests, including the rotation fix, with zero failures, errors or skips. Exact commands, the initial memory failure and successful logs are recorded in Section 18. These are local checks; cloud results are recorded separately.

## 16. Public-demo addendum — WEB-012: honest visitor information and working UI

The normal local application continues to use its existing content. The separate public demonstration needs an explicit explanation before someone enters account details or reserves a space: use test details, no charges are collected, no charger is activated, and free demo data can disappear. A new safe endpoint supplies that mode; it is not a generic endpoint for reading environment variables.

### New controller and DTO: `SiteInfoController`

**Created:** `src/main/java/com/evcharging/controller/SiteInfoController.java`. This class and its endpoint were absent at `3aa160a`.

The constructor reads only `APP_DEMO_MODE`, defaulting to false. `siteInfo()` returns its nested `SiteInfo(boolean demo, String notice)` record with `Cache-Control: no-store`. It publishes exactly those two intentional visitor fields and does not publish administrator identity, passwords, database addresses, provider metadata or other deployment configuration.

```java
public SiteInfoController(@Value("${APP_DEMO_MODE:false}") boolean demoMode) {
    this.demoMode = demoMode;
}

@GetMapping("/api/site-info")
public ResponseEntity<SiteInfo> siteInfo() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new SiteInfo(demoMode, demoMode ? DEMO_NOTICE : ""));
}
```

**New API:** anonymous `GET /api/site-info`, no request body, success 200, no authentication or CSRF required because it changes no state. Example normal response: `{"demo":false,"notice":""}`. Demo mode returns `demo:true` and this actual notice:

> Public demo: use test details only. No online charges are collected and no physical chargers are activated. Demo data may reset, and the free database may expire.

`SecurityConfig` explicitly permits this GET. Its safe DTO is deliberately smaller than the private account DTO; making this endpoint public does not make bookings, profile, inbox or customer directories public.

### Changes to the actual frontend files

| File / function | Before `3aa160a` | After / reason |
| --- | --- | --- |
| `static/index.html` | No server-mode notice container. | Adds initially hidden `#site-notice` with `role="note"` below the top bar. It consumes no visible space in normal mode. |
| `static/assets/styles.css` | Existing responsive blue/slate/coral theme. | Adds responsive coral banner and dialog-notice styles. Existing colors, local assets and reduced-motion behavior remain. No new external font, image or framework is introduced. |
| `static/assets/app.js`: state and `loadSiteInfo` | Startup loaded account/application data, without a demo-mode request. | Adds `siteInfo`, starts the optional request in parallel, and catches its failure. The mode request uses a two-second timeout, so ordinary station/account screens can still work if visitor information is temporarily unavailable. |
| `app.js`: `renderDemoNotice` / `renderDialogDemoNotice` | No banner or pre-form demo notice. | Writes the server notice with `textContent`; repeats it inside signup/login and reservation-review dialogs before their forms. It does not insert returned configuration as raw HTML. |
| `app.js`: `doAction` / `showContent` | Information dialogs always used the normal static content object. | The first relevant action waits for the same bounded mode request, shows a temporary busy state, then opens signup/reservation/help/privacy/guide with the applicable information. Later actions reuse the settled result. |
| `static/assets/api.js`: `request` | Every request had a fixed 30-second timeout. | Adds optional `timeoutMs`, retaining the 30-second default. Only the optional visitor-information request uses two seconds; CSRF, cookies, safe errors and mutation retry behavior stay the same. |
| `static/assets/content.js`: `publicDemoNotice` | No mode-specific notice selection. | Displays a notice only for strict boolean `demo === true` with a string notice. The string `"true"` is not treated as an enabled mode. |
| `content.js`: `siteContent` | Help, Privacy and Booking guide had only normal-operation copy. | Returns demo-specific copies when enabled without mutating the original content; normal mode retains its previous content. Existing terminal-reservation replay feedback remains unchanged. |

The timeout change comes directly from the Git comparison:

**Before:**

```javascript
export async function request(path, { method = 'GET', body, headers = {}, silent = false } = {}) {
// ...
const timeout = setTimeout(() => controller.abort(), 30000);
```

**After:**

```javascript
export async function request(path, { method = 'GET', body, headers = {}, silent = false, timeoutMs = 30000 } = {}) {
// ...
const timeout = setTimeout(() => controller.abort(), timeoutMs);
```

The new mode request and safe rendering are actual implementation snippets, not pseudocode:

```javascript
try { state.siteInfo = await request('/api/site-info', { silent: true, timeoutMs: 2000 }); }
catch { /* Optional visitor information must not prevent the application from working. */ }
```

```javascript
const notice = publicDemoNotice(state.siteInfo);
const banner = $('#site-notice');
banner.hidden = !notice;
banner.textContent = notice;
```

Using `textContent` matters even for a server-supplied notice: the browser treats it as text rather than interpreting it as executable markup. Mode selection is separate from navigation, authentication and booking logic. A visitor still uses the real registration/login and database-backed reservation APIs; the notice does not substitute a mock workflow.

### Exactly what content was added

Demo Help, Privacy and Booking guide each start with an explanation to use invented test names/email addresses and a unique test password, avoid sensitive/payment information, and expect resets or free-database expiry. Privacy states that records belong to the separate hosted demonstration database and are unsuitable for durable records. It honestly states that no dedicated support contact or retention guarantee is published, rather than inventing an operator address. Help describes an application-only reservation, without promised connector compatibility, energy metering or a real charging arrangement. Booking guide states that the demo collects no reservation/charging payment and controls no physical station.

The default false mode hides demo notices and returns the previous normal content. A missing or failing optional site-info request does not permanently block the application. That availability trade-off creates a deployment verification obligation: confirm that the hosted endpoint actually returns `demo:true` and that notices appear before the relevant forms. A home page loading successfully alone is insufficient evidence that demo mode was configured correctly.

### WEB-012 tests and current evidence

| File | What was added / modified | Evidence state |
| --- | --- | --- |
| `src/test/java/com/evcharging/controller/SiteInfoControllerTest.java` | Two standalone MVC cases: false/true mode, exactly two response fields, honest limitations and no-store header. | Both passed in the final 140-test H2 and PostgreSQL runs; see Section 18. |
| `src/test/frontend/public-demo.test.mjs` | Five cases for strict mode/notice handling, unchanged normal content, ephemeral-demo privacy and no false payment/hardware claims. | Frontend agent executed successfully as part of the 17-case command below. |
| `src/test/frontend/api.test.mjs` | Adds a timeout regression against the actual shared HTTP module. | Included in the same executed command. |
| `src/test/frontend/README.md` | Updates the exact command and explains the 17-case scope. | Documentation; no browser-success claim implied. |

The frontend agent recorded successful syntax checks of `app.js`, `api.js` and `content.js` with `node --check`, and this exact successful command:

```powershell
node --test src/test/frontend/api.test.mjs src/test/frontend/reservation-feedback.test.mjs src/test/frontend/public-demo.test.mjs
```

Result recorded by that executed frontend verification: **17 tests passed, zero failed, zero skipped**. These tests use controlled network responses and prove frontend logic. They do not prove live cloud availability, rendered-browser behavior or Java test success. The two new controller cases and twelve new security cases increase the previous 126-case Java suite to 140; all 140 passed in each final H2 and PostgreSQL run. See Section 18 for the exact evidence.

### Incremental file inventory for WEB-011 and WEB-012

This supplements the historical inventory in Section 13. Actions below are relative to `3aa160a`; no existing source file was deleted by these changes. WEB-013 will separately document deployment/configuration files.

| File | Action | Reason | Change |
| --- | --- | --- | --- |
| `src/main/java/com/evcharging/security/SecurityConfig.java` | Modified | Install pre-CSRF session protection; allow safe public site-info GET. | WEB-011/012 |
| `src/main/java/com/evcharging/security/SessionAdmissionLimiter.java` | Created | Bounded per-client fresh-session quota and expiry. | WEB-011 |
| `src/main/java/com/evcharging/security/SessionAdmissionFilter.java` | Created | Guard actual session creation; safely report pre-controller failures. | WEB-011 |
| `src/main/java/com/evcharging/security/SessionLimitException.java` | Created | Public-safe 429/503 allocation failures. | WEB-011 |
| `src/main/java/com/evcharging/security/SessionLimitExceptionHandler.java` | Created | Consistent MVC allocation errors and Retry-After. | WEB-011 |
| `src/main/java/com/evcharging/security/SessionCapacityConfig.java` | Created | Actual atomic Tomcat cap, including session-ID rotation. | WEB-011 |
| `src/test/java/com/evcharging/security/SessionAdmissionFilterTest.java` | Created | Fresh-session admission, reuse, missing CSRF, expiry and bounded map. | WEB-011 |
| `src/test/java/com/evcharging/security/SessionCapacityTest.java` | Created | Concurrent creation, released capacity and rotation-race regression. | WEB-011 |
| `src/test/java/com/evcharging/security/RenderProxyBoundaryTest.java` | Created | Actual native valve and checked-in trust-boundary configuration. | WEB-011 |
| `src/test/java/com/evcharging/security/SessionCapacityHttpIntegrationTest.java` | Created | Real embedded HTTP capacity/error/reuse/release behavior. | WEB-011 |
| `src/test/java/com/evcharging/auth/AuthSecurityIntegrationTest.java` | Modified | Verify public site-info DTO access creates no session. | WEB-011/012 |
| `src/main/java/com/evcharging/controller/SiteInfoController.java` | Created | Safe public demo-mode DTO and no-store response. | WEB-012 |
| `src/test/java/com/evcharging/controller/SiteInfoControllerTest.java` | Created | Normal/demo modes, exact DTO shape and caching behavior. | WEB-012 |
| `src/main/resources/static/index.html` | Modified | Accessible, initially hidden notice container. | WEB-012 |
| `src/main/resources/static/assets/styles.css` | Modified | Responsive coral notices consistent with the current theme. | WEB-012 |
| `src/main/resources/static/assets/app.js` | Modified | Load mode safely; show banner/pre-form notices and applicable content. | WEB-012 |
| `src/main/resources/static/assets/api.js` | Modified | Optional short timeout while retaining normal request defaults. | WEB-012 |
| `src/main/resources/static/assets/content.js` | Modified | Separate, honest demo information without changing normal content. | WEB-012 |
| `src/test/frontend/public-demo.test.mjs` | Created | Five demo-content regressions. | WEB-012 |
| `src/test/frontend/api.test.mjs` | Modified | Visitor-information timeout regression. | WEB-012 |
| `src/test/frontend/README.md` | Modified | Updated commands and evidence limits. | WEB-012 |
| `docs/WEBSITE_COMPLETION_CHANGE_REPORT.md` | Modified | Preserve local-release history and explain the later public-demo changes. | WEB-011/012/013 |

## 17. Public demo deployment preparation — WEB-013

This follow-up uses the completed local-website commit `3aa160a` as its before-state. The original local audit and test evidence above remain historical. The user then approved a free public demonstration with a **separate fresh cloud database and no online charges**. The existing local database named `EVCHARGING` is unchanged; its preservation/migration still needs the private backup, schema audit and restored-clone rehearsal.

The deployment preparation adds no Java framework, microservice, payment provider, Redis instance or database migration. It packages the existing same-origin website/API for one Render web instance and one new managed PostgreSQL database. Account authorization and a verified deployment are separate from these source changes; no public URL is invented here.

### WEB-013A — Listen on the hosting provider's assigned port

**File:** `src/main/resources/application.properties`.
**Class/method:** Spring Boot configuration; no Java class changed by this item.

**Before, retrieved from `3aa160a`:**

```properties
server.port=${SERVER_PORT:8081}
```

**After:**

```properties
server.port=${SERVER_PORT:${PORT:8081}}
server.forward-headers-strategy=none
```

The old configuration understood an explicitly supplied local `SERVER_PORT`, but did not read Render's `PORT`. Spring now resolves the explicit port first, then the provider port, then the existing local default of 8081. Local scripts retain their behavior. Direct runs explicitly ignore forwarding headers, so an internet client cannot change its apparent address simply by sending `X-Forwarded-For`. The Render profile deliberately enables forwarding at its own trusted boundary.

**Verification:** checked against the actual Git diff. Native forwarding behavior is covered by the security follow-up tests, rather than assumed from this property alone. Provider port detection still requires a real cloud startup check.

### WEB-013B — Leave container memory and shutdown headroom

**Files:** `Dockerfile`, `src/main/resources/application-prod.properties`.
**Class/method:** container and Spring lifecycle configuration; no Java class changed.

**Docker before, retrieved from `3aa160a`:**

```dockerfile
ENV SERVER_ADDRESS=0.0.0.0 SERVER_PORT=8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/evcharging.jar"]
```

**Docker after:**

```dockerfile
ENV SERVER_ADDRESS=0.0.0.0
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50.0", "-jar", "/app/evcharging.jar"]
```

Removing the fixed container `SERVER_PORT` lets the new provider-port fallback operate. `EXPOSE 8081` remains image metadata and does not override Spring's listening port. The maximum heap percentage was reduced from 70 to 50 to leave more space for class metadata, native allocations and HTTP threads on a Free instance. This is a conservative starting configuration; it is not evidence of a successful 512 MB load test.

The Dockerfile still builds with Java 17/Maven, executes the full JVM `clean verify` goal, and runs as an unprivileged user. No secrets are copied into the image. Frontend Node tests run separately in the verification workflow.

The production profile previously ended with `server.servlet.session.cookie.secure=true`. It retains that setting and now adds:

```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=20s
```

The application requests a 20-second Spring graceful-shutdown budget. The Render Free plan uses provider-controlled termination timing: the live Blueprint preview rejected a custom shutdown-delay setting, so that setting was removed. No provider grace period has been measured or guaranteed for this deployment. This does not promise uninterrupted service during free-plan sleep, maintenance or database expiry.

**Verification:** focused Git diff/whitespace review. Docker image execution and cloud shutdown timing require their own recorded runtime checks; they are not claimed by reading a Dockerfile.

### WEB-013C — Give Render a narrow deployment profile

**New file:** `src/main/resources/application-render.properties`.
**Class/method:** Spring/Tomcat/Hikari configuration; new session-bound implementation belongs to WEB-011.

**Before:** no Render-specific profile existed at `3aa160a`.

**After, focused settings:**

```properties
server.address=0.0.0.0
server.servlet.session.cookie.secure=true
server.forward-headers-strategy=native
server.tomcat.remoteip.remote-ip-header=X-Forwarded-For
server.tomcat.remoteip.protocol-header=X-Forwarded-Proto
server.tomcat.remoteip.internal-proxies=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,127.0.0.0/8,::1/128,fc00::/7
server.tomcat.remoteip.trusted-proxies=
server.tomcat.remoteip.host-header=
server.tomcat.remoteip.port-header=
spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=require
```

Tomcat processes IP/protocol forwarding only when the direct peer matches the private/loopback list, and stops walking a forwarded chain at an untrusted address. It does not trust every public sender. Unneeded forwarded host/port overrides are disabled. Actual Spring Boot 4.1.1 configuration metadata was inspected to confirm support for CIDR-list syntax. The real hosting edge must still be checked; a failure to distinguish clients is a reason to investigate the boundary, not to trust `.*`.

The database URL is assembled from Render's individual internal host/port/database properties. The username and password remain separate environment values; a native `postgres://` URL is not passed off as a JDBC URL. Render's internal TLS certificate is self-signed, so its official documentation specifies `sslmode=require` and does not support `verify-ca`/`verify-full` on that route. This encrypts traffic but does not provide public-CA hostname verification. The same-region private network provides the additional isolation boundary. [Render database connection documentation](https://render.com/docs/postgresql-creating-connecting)

Resource bounds are five Hikari connections with one idle connection; 40 maximum HTTP workers with two spare workers; 200 TCP connections; an accept queue of 50; 512 active sessions; and admission defaults of 30 new sessions per client per 300 seconds with at most 10,000 tracked clients. These settings bound resource consumption; they do not establish a measured traffic capacity.

**Trade-off:** only one application instance is configured because sessions and admission/attempt counters are process-local. A restart requires login again, while accounts/bookings remain in PostgreSQL. Scaling is a later deliberate session/edge-abuse design decision.

### WEB-013D — Describe only the two approved Free resources

**New file:** `render.yaml`.
**Class/method:** declarative infrastructure configuration; no Java class.

**Before:** no public-hosting Blueprint existed at `3aa160a`.

**After, focused structure:**

```yaml
services:
  - type: web
    name: voltway-public-demo
    runtime: docker
    plan: free
    region: singapore
    numInstances: 1
    autoDeployTrigger: "off"
    healthCheckPath: /actuator/health
databases:
  - name: voltway-public-demo-db
    plan: free
    region: singapore
    databaseName: evcharging_demo
    user: evcharging_demo
    postgresMajorVersion: "17"
    ipAllowList: []
```

The complete file wires `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME` and `DB_PASSWORD` through `fromDatabase`, enables `prod,render`, and sets `APP_DEMO_MODE=true`. Administrator email/password use operator-entered `sync: false` values. No common password or production credential is committed. Empty external-IP rules block public database access; the same-region app uses the internal connection.

Automatic deployments are off. Initial Blueprint creation still builds/deploys; later branch changes require a reviewed manual deploy. Both plans are explicitly Free so omitted-plan defaults cannot accidentally select paid compute. There is no paid disk, replica, high-availability database or Key Value service. The guide requires checking the account/resource preview and stopping if billing or a paid upgrade is required. [Render Blueprint reference](https://render.com/docs/blueprint-spec)

**Verification actually performed:** the file validated successfully against Render's downloaded official Draft 2020-12 JSON schema. Additional assertions passed for the Free plans, single instance, matching regions, fresh DB name, private DB access, manual deployment, correct DB references and unsynced administrator inputs. This is a real local validation result, not proof that a Render account has accepted or run the resources.

### WEB-013E — Verify the application without automatically publishing it

**New file:** `.github/workflows/verify.yml`.
**Class/method:** GitHub Actions workflow; no application class changed.

**Before:** no verification workflow existed at `3aa160a`.

**After:** push, pull-request and manual triggers use a read-only token, Java 17 and Node 24. Maven first runs H2 `clean verify`, then PostgreSQL `clean test` against a disposable localhost PostgreSQL 17 container named `evcharging_test_ci`. The strict test-database guard prevents this fixture from pointing at a normal application database. Its visible password is an isolated CI-only fixture, never a live credential.

JavaScript syntax is checked for `api.js`, `app.js` and `content.js`; all `src/test/frontend/*.test.mjs` tests run. The workflow retains separate H2/PostgreSQL test reports for seven days, cancels superseded work, has a 25-minute job timeout and contains no deployment command or cloud secret. Actions versions follow the current official action READMEs checked during this task.

**Verification:** workflow YAML parsed successfully and assertions confirmed the expected test-only commands and read-only token. Actual GitHub-hosted execution is separately observable after publishing; it is not claimed by local parsing. A local Maven/Node test pass is not fabricated as a completed GitHub Actions run.

### WEB-013F — Explain setup, limits and safe demo operation

**Files:** `.env.example`, `README.md`, `docs/PUBLIC_DEMO_DEPLOYMENT_GUIDE.md`.
**Class/method:** configuration template and documentation content.

The existing environment template now adds `APP_DEMO_MODE=false` for local use and explains the separate Render DB variables. The Blueprint sets demo mode true. The README links the prepared deployment and distinguishes it from the already verified local application.

The new guide walks through selecting the reviewed branch, approving only Free resources, entering private administrator settings, waiting for healthy startup, and testing the actual HTTPS UI. It explains the JDBC URL, trusted proxy list, memory/session limits, manual releases and the difference between archive inspection and a real restore rehearsal. A Windows backup example uses a secure password prompt, restores temporary environment variables, restricts the backup folder and stores it outside the repository. Its PowerShell syntax was parsed successfully with **zero errors**; the example was not executed against a cloud database.

The guide clearly states the provider limits verified in official documentation: Free web sleep after 15 idle minutes, shared monthly free hours/usage allowances, a 1 GB Free database that expires after 30 days with a later 14-day upgrade grace period, and no managed Free database backups. No upgrade, paid recovery, monitoring automation, live payment or charger activation is authorized or invented. Use test information and export anything useful before expiry. [Render Free documentation](https://render.com/docs/free)

### WEB-013 verification record and files

Executed local verification commands:

```powershell
& 'C:/Users/kshit/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' 'work/validate-public-deployment.py'
git diff --check -- Dockerfile .env.example README.md src/main/resources/application.properties src/main/resources/application-prod.properties
```

The actual Python executable used was `C:/Users/kshit/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`. The workspace-only script used PyYAML/jsonschema under `work/schema-validation-libs`; these tools are not application dependencies. The schema source was `https://render.com/schema/render.yaml.json`. Results: official-schema validation passed; deployment safety assertions passed; workflow parsing/assertions passed; focused Git whitespace check passed (Git printed line-ending normalization notices). The guide's extracted PowerShell example was checked using `System.Management.Automation.Language.Parser.ParseFile`, with zero syntax errors and no execution.

| File | Action since `3aa160a` | Concrete reason |
|---|---|---|
| `Dockerfile` | Modified | Let provider PORT reach Spring; reduce maximum heap to leave native-memory headroom. |
| `src/main/resources/application.properties` | Modified | Provider port fallback and explicit default forwarding policy. |
| `src/main/resources/application-prod.properties` | Modified | Explicit graceful shutdown with a bounded budget. |
| `src/main/resources/application-render.properties` | Created | Private managed DB/TLS, narrow proxy trust and free-instance resource bounds. |
| `render.yaml` | Created | Two explicit Free resources, separate fresh DB, secret references and manual release. |
| `.github/workflows/verify.yml` | Created | Repeatable H2/PostgreSQL/frontend verification without deploying. |
| `.env.example` | Modified | Document demo mode and separate cloud configuration without credentials. |
| `README.md` | Modified | Point to deployment instructions and distinguish demo/cloud/preservation scope. |
| `docs/PUBLIC_DEMO_DEPLOYMENT_GUIDE.md` | Created | Account setup, verification, limits, backups and troubleshooting in plain English. |

Source/static checks above are complete. Docker execution, a GitHub Actions run, live Render performance, actual HTTPS/client-IP checks and cloud restore verification must be recorded only if they are performed. Existing local `EVCHARGING` preservation/migration remains pending; preparing a fresh-cloud demo does not resolve or bypass it.

## 18. Public-demo verification and publishing status

This section records the checks executed on 18 October 2026 after WEB-011/012/013, including the session-ID rotation fix. These results supersede the earlier 126-test counts for the updated source. They do not turn historical local checks into cloud checks.

### Actual automated results

| Check | Actual result | Evidence |
| --- | --- | --- |
| Initial H2 `clean test`, default JVM sizing | Build failed: 95 tests completed, zero failures/errors/skips, then the forked JVM stopped on native-memory allocation failure. | `work/public-demo-h2-test.log` and its Surefire dumpstream; not counted as a successful build. |
| H2 `clean verify`, bounded JVM memory | **140 executed; 140 passed; zero failed, errored or skipped. BUILD SUCCESS.** Runnable Spring Boot JAR packaged. Duration 2:32; finished 18 October 2026 at 11:23:08 +05:30. | `work/public-demo-h2-verify.log`; preserved XML reports under `work/public-demo-h2-reports`. |
| Isolated PostgreSQL 17.11 `test`, same final source | **140 executed; 140 passed; zero failed, errored or skipped. BUILD SUCCESS.** Duration 1:25; finished 11:25:33 +05:30. | `work/public-demo-postgres-test.log`; URL restricted to loopback and `evcharging_test_20261017`. |
| Frontend Node tests | **17 executed, 17 passed, zero failed/skipped.** | `work/public-demo-frontend-tests.log`. |
| Official Render JSON schema | Passed, including extra Free-only, private fresh DB, one-instance and manual-release checks. | `work/validate-public-deployment.py`, official downloaded schema; no application dependency added. |
| CI YAML/static safety checks | Passed local parsing/assertions. Workflow jobs have not been inferred to pass from YAML parsing. | Same validation script. |

The local machine has about 6 GB physical memory. The first fork's native allocation failure is an environment failure, not a successful test run or an asserted application defect. The rerun bounded Maven's heap and the test fork's heap and reduced the processors visible to the JVM. No test was removed, disabled or skipped to obtain success. No application POM change was needed for these local verification settings.

### Exact Maven commands and environment

The actual JDK was Temurin **17.0.20.1+1**, matching the declared Java 17 compilation release. From the repository root, the successful commands were:

```powershell
$env:JAVA_HOME='C:/Users/kshit/Documents/Codex/2026-10-17/codex-master-prompt-ev-charging-spring/work/runtime/jdk17/jdk-17.0.20.1+1'
$env:MAVEN_USER_HOME='C:/Users/kshit/Documents/Codex/2026-10-17/codex-master-prompt-ev-charging-spring/work/maven'
$env:TEMP='C:/Users/kshit/Documents/Codex/2026-10-17/codex-master-prompt-ev-charging-spring/work/tmp'
$env:TMP=$env:TEMP
$env:MAVEN_OPTS='-Xmx160m -XX:ReservedCodeCacheSize=48m -XX:ActiveProcessorCount=2'
.\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$env:MAVEN_USER_HOME/repository" '-DargLine=-Xmx384m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2' clean verify
```

The failed initial command used the same JDK/cache/temp configuration and `clean test`, without the `MAVEN_OPTS` and `argLine` memory bounds. It was recorded separately rather than overwritten by the successful log.

Before the PostgreSQL run, process-scoped `EV_TEST_POSTGRES_URL` was set to `jdbc:postgresql://127.0.0.1:55433/evcharging_test_20261017`. The username/password were loaded from the agent-owned local verification-cluster metadata; their values are intentionally omitted. The command was:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$env:MAVEN_USER_HOME/repository" '-DargLine=-Xmx384m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2' test
```

`test` reran the complete suite against PostgreSQL without deleting the already verified packaged JAR. The private password variable was removed afterwards. The existing local PostgreSQL 14 `EVCHARGING` and `reality` databases were not used. The test guard rejects application database names, remote hosts and URL parameters.

The actual Node executable was the bundled `C:/Users/kshit/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe`, with:

```powershell
node --test src/test/frontend/api.test.mjs src/test/frontend/reservation-feedback.test.mjs src/test/frontend/public-demo.test.mjs
```

### Runtime, publishing and live checks

**Live Blueprint preview and correction:** Render rejected the first resource preview with `services[0].maxShutdownDelaySeconds: max shutdown delay is not supported for free tier services`. The earlier official JSON-schema check was a genuine structural pass, but it did not encode this account/plan restriction. The unsupported field was removed from `render.yaml`; both resources remain explicitly Free, and the Spring 20-second graceful-shutdown configuration remains. The guide and WEB-013 now describe provider-controlled, unmeasured termination timing rather than a configured 30-second Render allowance. The corrected file passed the same official-schema/static checks and a focused Git whitespace check. Acceptance of a retried live preview and an actual cloud startup must still be recorded separately; no Java change or new application test result is implied by this configuration-only correction.

<!-- PUBLIC_DEMO_FINAL_STATUS -->

The reviewed source was published to `codex/backend-audit-improvements`, with draft pull request [#1](https://github.com/Kij0007/EV-Charging-Backend/pull/1). `main` remains the original commit; there was no merge, force-push or release. The local Git credential helper could not run in this Windows sandbox, so the authenticated GitHub connector published a source snapshot. Its root tree `84f35b2dc5227547f82688c92c4b4b28137fc4bb` exactly matched local commit `87bb6f3`; the GitHub commit was `4b81ef8cba20f9cceb3171c91e6421379578ef82`. Commit identifiers differ because GitHub created new commit metadata. Original local audit/website history remains in the supplied workspace; the original before-snippet hashes in this report refer to that preserved history.

[GitHub Actions run 39102414588](https://github.com/Kij0007/EV-Charging-Backend/actions/runs/39102414588) completed successfully. The actual job logs report **140 tests, zero failures/errors/skips for H2 `clean verify` and 140 for disposable PostgreSQL `clean test`**, plus **17 passing frontend tests** and successful JavaScript syntax checks. These were actual Ubuntu CI jobs, not just YAML validation. Later configuration/documentation-only corrections are tracked separately.

The packaged JAR was also started with demo mode enabled against a newly created agent-owned local database, `evcharging_demo_local_verify`, on PostgreSQL 17 loopback port 55433. The successful scratch launcher explicitly loaded the packaged `application` configuration and supplied `PORT=18083` without `SERVER_PORT` or a command-line server-port override. HTTP checks passed for health UP, exactly the public `{demo,notice}` DTO with `demo:true` and `no-store`, and the website entrypoint. None of these public GETs allocated a session cookie.

The browser showed the persistent demo notice, the notice before the signup form, the correct empty-directory state for a fresh database, and the demo-specific privacy dialog. No real station or user data was imported. Scratch process launches were corrected before this successful check: a detached launch did not survive its tool session, and cleared configuration-name environment variables suppressed the normal application configuration. The final direct foreground launch explicitly restored that configuration. These scratch-launch problems were not source-code changes or successful PORT checks.

The verifier used `-XX:MaxRAM=512m -XX:MaxRAMPercentage=50.0`, plus the intended smaller pool/thread/session settings. This proves a functional local startup with that heap sizing; **it is not an enforced 512 MB operating-system limit, a traffic/load test, or a measured Render memory guarantee**.

The owner subsequently created the Free resources. Render deployed commit `25e0629dfe78bd0d48e849853c3cbb29e08aa887`, and the public HTTPS website and customer authentication were directly verified. Section 19 preserves those historical results and the checks left incomplete when the user cancelled Render. The web service is now suspended; Section 20 is the current PC-hosted approach. The actual Free-plan preview rejected a setting accepted by the general schema; that setting was removed before creation.

Docker was not found on this machine; no local Docker-image build is claimed. Render subsequently built and ran the Docker image successfully; Section 19 records the deployed revision and startup evidence. The Docker builder retains `clean verify`. Native proxy-boundary tests prove the configured valve behavior; the real Render peer chain, Secure-cookie login, independent-client throttling and hosted database persistence still need a live check.

The mandatory preservation of existing local data remains a separate pending task. A new demo database does not migrate old accounts or grant access to their booking history.

## 19. WEB-014 — Historical Render rollout, subsequently suspended

This section records the earlier operational rollout on **18 October 2026**. WEB-013 describes the source/configuration changes that made deployment possible. WEB-014 explains what was actually provisioned and observed after the owner approved the Free resources and clicked **Deploy Blueprint**. The user later cancelled this hosting choice; **Suspend Web Service** was performed and the interface showed **Suspended**. The separate cloud database was retained. Section 20 supersedes the hosting recommendation below. No additional Java class, controller, entity or DTO was introduced by WEB-014.

### What was deployed at the historical cloud checkpoint

The historical public address assigned to that now-suspended service was:

**[https://voltway-public-demo.onrender.com/](https://voltway-public-demo.onrender.com/)**

This is not the current address to share. Render reported a successful deployment of these resources at that earlier checkpoint:

| Item | Verified deployment information |
|---|---|
| Web service | `voltway-public-demo`, Free plan |
| Database service | `voltway-public-demo-db`, Free plan; new database for this demo |
| Source commit | `25e0629dfe78bd0d48e849853c3cbb29e08aa887` |
| Render deployment | `dep-dba66h3ncjis73fu6c3g` |
| Provider deployment result | Succeeded; total deployment duration **4 minutes 43 seconds** |
| First successful startup | **18 October 2026, 11:47:03 IST** |
| Runtime seen in logs | Java **17.0.20.1**, Spring Boot **4.1.1**, active profiles **prod** and **render** |
| Database seen by the app | PostgreSQL **17.11** |
| Schema initialization | Flyway applied **V1 and V2** to the fresh database; Hibernate initialized successfully |
| HTTP listening port | Render's dynamic internal **10000** port |
| Initial application startup duration | **150.208 seconds** reported by the application |
| Administrator bootstrap | Logs confirmed that the initial administrator was provisioned; no password or provider secret was read or recorded |

The provider's successful deploy is evidence that it built and started the application and accepted its deployment checks. It is not a substitute for independently testing account login, permissions and booking behavior through the public website. Those checks are kept separate below.

### Before and after: where the program runs

Previously, `http://127.0.0.1:18082/` or another selected local port reached Java running on the developer's computer. That address was useful for local testing; it did not give other people a public website.

At that cloud checkpoint, Java ran on Render's infrastructure and PostgreSQL ran as a separate managed resource. Visitors used the assigned HTTPS address rather than the developer's loopback address. That setup was independent of the developer's PC, subject to Free sleep/usage/database-expiry limits. This paragraph describes the earlier architecture only: the Render web service is now suspended, and the selected PC-hosted approach requires the PC, app, database and tunnel to stay available.

```text
Visitor's browser
    -> HTTPS public address, normally port443
    -> Render's managed TLS/HTTP edge
    -> Java application on internal PORT10000
    -> private connection to the separate managed PostgreSQL database
    -> response returns through the same web service
```

Port443 and port10000 have different jobs. Port443 is the visitor-facing HTTPS connection. Port10000 is where this Java process listens inside the hosting environment. Visitors do not need to add `:10000` to the public URL. The provider forwards traffic between them.

### Why the same UI can use the cloud backend

The JAR contains both the website assets and the Spring MVC API. The browser loads `/` and `/assets/...` from the deployed service, then calls relative paths such as `/api/stations` and `/api/bookings` on that same origin. There is no hardcoded local API host to replace for this deployment.

For example, `/api/stations` resolves under the public hostname when the page is opened publicly, and under `127.0.0.1` when it is opened locally. This keeps the UI/API connection understandable and avoids introducing a separate frontend deployment, CORS allowance or cross-site authentication design.

The existing request flow still applies:

```text
HTTP request -> Spring Security -> Controller -> Service
             -> Repository -> PostgreSQL -> Response DTO -> UI
```

Hosting changes the process location and configuration, not the responsibility of `AuthController`, `WebsiteApiController`, `BookingServiceImpl`, the repositories or their DTOs. Their behavior is documented in the earlier class-by-class sections. A public URL does not automatically establish that each step of that flow has been manually verified online.

### Keep cloud credentials and local data separate

The owner entered the initial administrator values in Render's setup controls. The Blueprint obtains the database connection components from the new managed database through provider references. Credentials are runtime settings, not source code, Docker image contents or values to print in this report.

The application activated `prod,render`. These profiles select the cloud JDBC connection and Secure session cookie settings; they do not automatically copy data from a developer's computer. Fresh Flyway migrations created the cloud schema deliberately.

**The existing local database named `EVCHARGING` was not connected, uploaded, baselined, overwritten or migrated during this cloud rollout.** Its old users and booking history do not appear in the new demo. The separate preservation/migration task still requires a private backup, actual schema audit and rehearsal against a restored clone before changes are planned for it.

No original local database password, local administrator-access file or data dump was uploaded as part of this deployment. The fact that the new cloud schema initialized successfully says nothing about compatibility with the older local schema.

### What the public demonstration promises

The software implements customer/admin accounts, authorization, station records, immediate reservations, cancellation/completion and in-app notifications. New reservations collect no money. It has no payment charge, named physical connector, charger-control protocol or future appointment calendar.

The public demo notice asks people to use test details. A station created for a demonstration must be labeled as a test station; it is not evidence that a real roadside charger exists or can be activated. This distinction remained necessary even while the application ran on the earlier public cloud service.

### What happened when the Free service slept

After the first successful startup, logs recorded graceful shutdown at **12:02:03 IST**. The first visitor arrived around **12:17 IST**, which woke the Free web instance. The observed shutdown followed the initial startup by 15 minutes, consistent with the idle-sleep limit documented by the provider.

The restarted process later completed startup in **159.603 seconds**, listening on internal port10000 at **12:20:13 IST**. It retained schema version2 and skipped administrator bootstrap because that account already existed. Two early HTTP probes timed out during wake-up; a later probe at about **12:22 IST** returned HTTP200 and health UP. The timeouts remain failed early attempts, not successful tests.

The first measured Java startup took about two and a half minutes. A short request can therefore time out while a sleeping instance is still waking. This is an observed Free-demo limitation, not a reason to disable security/migrations or silently switch to paid hosting. Later HTTP observations should be recorded with their actual results before declaring the public user journey verified.

Sessions and admission/attempt counters are in the Java process. A stop/restart can require the visitor to log in again. Database records are in the separate managed PostgreSQL service, so a web-process restart is different from deleting its database. Database expiry, however, is a separate Free-plan limit: the database becomes unavailable after its configured free lifetime. There is no permanent-retention or continuous-availability promise.

### Historical cloud verification record at that checkpoint

| Area | Actual evidence / current boundary |
|---|---|
| Owner authorization and resource creation | Owner approved the Free-resource preview and clicked Deploy Blueprint. |
| Source/configuration | The deployed commit is identified above; WEB-013 records its files and the removed unsupported shutdown override. |
| Provider build/startup | Deployment succeeded; actual Java/Boot/profile/database/migration/port details were observed in logs. |
| GitHub Actions | Runs `39102842952` and `39102840437` reported success. Detailed test counts should be taken from their execution logs, not inferred from a green badge. |
| Public HTTPS GET after wake | Verified HTTP200 for the website, JavaScript, directory, health and demo-mode information. HTTP redirects301 to the expected HTTPS origin; normal certificate and hostname validation remained enabled. |
| Cloud login / cookie / permissions | Disposable-account HTTP verification passed registration, incorrect-password401, correct login, session/CSRF rotation, profile/inbox access, USER denial on admin APIs, CSRF protection and logout. Cookie flags Secure/HttpOnly/SameSite=Lax/host-only/path-root passed. Administrator browser access is being resolved separately. |
| Cloud booking/cancel/complete/inbox/profile flow | **Pending direct verification using clearly labeled test data.** |
| Actual Render forwarded client-IP behavior | **Pending live verification.** Synthetic Tomcat tests establish configuration behavior only. |
| Record persistence after a controlled cloud restart | **Pending an explicit before/after check.** Separate managed storage is the architecture, not proof of that test. |
| Existing local data migration | **Still pending and separate.** No original data was altered by the fresh-cloud rollout. |

No additional Java class/file was created for WEB-014. Its meaningful changes are operational: an approved source revision was built and run by the provider, a new database was initialized, and a public route was assigned. The final report/PDF and access instructions should be updated with the direct HTTP/browser results once observed, without changing this checkpoint's failed/pending checks into fabricated successes.

### Historical cloud next steps, superseded by cancellation

These were the proposed next checks before cancellation. They are preserved as historical scope; do not resume Render on their authority. Current work follows Section 20.

1. Wait for the waking instance to finish startup, then directly check HTTPS `/`, required assets, `/actuator/health` and `/api/site-info`. The public endpoints must not create a session merely by being viewed; the site-info response must say demo mode is enabled.
2. Verify a test customer's registration/login/refresh and ordinary/admin permission boundaries. Check Secure/HttpOnly/SameSite cookie attributes without printing token/cookie values.
3. Use a clearly labeled test station to verify reserve/cancel and administrator completion, including capacity returning only once, the customer's own booking list, notifications and account controls.
4. If a controlled restart is performed, compare saved record details before/after and expect to log in again. Do not delete the database, switch to a stale local database or treat sleep as a data-reset command.
5. Record the actual database expiry date from the provider and plan any needed private export before then. No paid upgrade, backup subscription, online charge or recurring automation is authorized merely by publishing this demo.


### Direct online verification: actual commands and results

```powershell
& 'C:/Users/kshit/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' work/verify_public_site.py
& 'C:/Users/kshit/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' work/public_transport_check.py
```

The successful authentication run completed at **12:26:22 IST**, with **33 HTTP/security assertions passed**, using an invented test account stored only in a private scratch credential file. It took16.704 seconds. The sanitized results are `work/public-qa-second-run.log` and `work/public-qa-results.json`. These assertions are separate from the140-case Java suite; they are not33 new JUnit tests. The transport check returned HTTP301 to the expected HTTPS address and HTTP200/demo:true over a normally verified TLS connection.

An earlier verifier run requested HTML while advertising only `Accept: application/json`; Spring correctly returned406. The verifier's static-resource Accept headers were corrected, then the full run passed. This was a test-tool error and required no application change. An earlier inline transport probe also had a local quoting syntax error; the saved script replaced that command and executed successfully. No failed probe is counted as a passed application test.

The real browser opened the cloud website and sign-in form with its demo notice. The owner reported incorrect administrator credentials; the independent customer test established cloud customer authentication, not that the administrator password was known or that administrator booking operations were completed. Administrator access and cloud booking/cancellation/completion were left unverified when the user cancelled Render. They are historical limits of that rollout, not instructions to resume the suspended service. No password was requested in chat or read from Render secrets, and bootstrap does not overwrite an existing account's password on restart.

<!-- GLOBAL_FINAL_FUNCTIONAL_RESULTS -->

## 20. WEB-015 — Current choice: run the website on the PC with a fixed Free ngrok link

### The current decision and the earlier Render work

The user changed the hosting decision: **Spring Boot and PostgreSQL should run on the owner's Windows PC, reached through an account-assigned Free ngrok HTTPS address.** This is now the selected public-access architecture. The PC, internet connection, PostgreSQL, Java application and ngrok agent must remain running. A fixed hostname can be reused; it does not keep a switched-off computer online.

The earlier Render rollout in Section 19 is **historical**. It records a real cloud build, separate cloud database and observed cloud behavior at that earlier checkpoint. It is not the current hosting recommendation or the current address to share. Its independence from the owner's PC was a property of that previous setup, not of the new PC-hosted choice.

After cancellation, the actual Render operation was **Suspend Web Service**. The Render interface showed the web service as **Suspended**. Suspension is different from deletion: the cloud Free PostgreSQL database was retained, because irreversible database deletion was not authorized. No claim is made that cloud data, the service definition or all cloud resources were permanently removed. The retained Free database remains subject to its provider's rules; those cloud-expiry rules do not describe the PC's PostgreSQL database.

The account-assigned hostname `unbaked-skyrocket-abide.ngrok-free.dev` was observed through a read-only account domain listing. That is not a verified running endpoint. No token was configured and no public ngrok user journey was completed at this checkpoint. The usable HTTPS forwarding address still needs an authenticated agent startup and direct verification. Account setup, an assigned hostname, source preparation and successful public login are separate checkpoints.

### What changed, and what remains the same

There are four new repository files for WEB-015, plus a README update and a separate workspace-specific owner launcher at `outputs/Start-PC-Public-Website.ps1`. **No production Java class, controller, DTO, entity, repository or business service was changed for this hosting choice.** The existing login, ownership checks, CSRF protection, credential-version revocation, bounded sessions, reservation locking, idempotency and inbox remain the same implementation.

The website and its APIs still share one Spring Boot origin. Relative calls such as `/api/bookings` automatically use the public ngrok hostname. There is no separate frontend deployment and no permissive CORS rule to add. Reservations still hold current capacity; there is no online charge, physical charger activation, future appointment or charging telemetry integration.

```text
Visitor's browser
  -> account-assigned ngrok HTTPS address
  -> ngrok edge and secured tunnel
  -> ngrok agent on the owner's PC
  -> Spring Boot at http://127.0.0.1:18082
  -> the explicitly selected, reviewed local PostgreSQL database
```

Only the HTTP application is shared. PostgreSQL is not exposed through a public database/TCP tunnel. Router port forwarding, a Windows service and a LAN-wide application bind are not needed by this design.

### WEB-015A — A dedicated Spring profile for the local tunnel

**New file:** `src/main/resources/application-tunnel.properties`.

**Class/method:** None; this is a Spring Boot configuration profile. Activating `local,tunnel` loads the ordinary local configuration and then the later tunnel profile's explicit overrides.

**Before:** The normal application deliberately ignored forwarded headers. Local HTTP development allowed a non-Secure session cookie. These focused snippets come from the actual retained files:

```properties
# application.properties
server.address=${SERVER_ADDRESS:127.0.0.1}
server.forward-headers-strategy=none
server.servlet.session.cookie.secure=false

# application-local.properties
server.address=${SERVER_ADDRESS:127.0.0.1}
server.servlet.session.cookie.secure=false
```

Those defaults are appropriate for ordinary local-only HTTP development. By themselves they do not define a public HTTPS proxy boundary.

**After:** The new tunnel profile contains:

```properties
server.address=127.0.0.1
server.servlet.session.cookie.secure=true
server.forward-headers-strategy=native
server.tomcat.remoteip.internal-proxies=127.0.0.1/32,::1/128
server.tomcat.remoteip.trusted-proxies=
server.tomcat.remoteip.remote-ip-header=X-Forwarded-For
server.tomcat.remoteip.protocol-header=X-Forwarded-Proto
server.tomcat.remoteip.host-header=
server.tomcat.remoteip.port-header=
app.security.max-active-sessions=512
```

**Why:** A browser visits ngrok over HTTPS, while the agent reaches Spring over local loopback HTTP. The application needs to recognize the trusted forwarded visitor address/protocol and retain Secure authentication cookies. The forwarding sender must be narrowly defined so an arbitrary public or LAN caller cannot choose an identity by supplying `X-Forwarded-For`.

**How it works:** Spring Boot configures the existing Tomcat `RemoteIpValve` through the native forwarding properties. Only the local agent at IPv4/IPv6 loopback is an internal proxy. LAN/private ranges and other public peers are not trusted by this profile. Tomcat examines the forwarded address chain from the right and stops at the first untrusted address. Given a forged prefix followed by the actual visitor appended by ngrok, the appended visitor controls the observed address rather than the attacker's leftmost text. Forwarded host/port overrides remain disabled.

Protocol-chain handling is conservative: mixed `http` and `https` values do not make an insecure request secure. A Secure cookie tells the browser to send the session cookie over HTTPS only. **That flag is not itself an HTTP-to-HTTPS redirect.** The real public endpoint must redirect/reject HTTP before anyone enters credentials. The actual agent/edge chain and browser cookie behavior require live verification; a synthetic valve test does not establish them.

**Trade-offs and boundary:** All trusted forwarding ends at the owner's loopback interface. Processes already running on that PC are inside this trust boundary; this does not protect the owner from an untrusted local process. Keep the application off the LAN and do not widen trusted proxies to work around login issues. Ordinary HTTP localhost login remains available through the separate normal `local` startup mode. When `tunnel` is active, use the public HTTPS address for authenticated operation, including from the host PC. Local HTTP health checks can still work.

**Data compatibility:** The profile contains no datasource, Flyway, administrator or demo-mode override. It does not select, copy, migrate, baseline, reset or delete a database. Existing `EVCHARGING` data still requires the earlier backup/schema-audit/restored-clone process before adopting that database. The PC must use an explicitly selected reviewed database with compatible migrations. `APP_DEMO_MODE=false` is appropriate for ordinary PC operation, because the former cloud demo's Free-database-expiry notice does not describe local storage. Existing production/Render profiles are preserved for their own contexts.

### WEB-015B — Start only the reviewed HTTP tunnel

**New file:** `ops/Start-PublicTunnel.ps1`.

**Class/method:** This is a PowerShell script, not a Java class. Its public parameters are `NgrokPath`, `Port`, `PublicDomain` and `PreflightOnly`. `Test-SecureSessionCookie` validates response-cookie attributes before a public tunnel can start; `ConvertTo-HealthJsonText` normalizes the health response representation before JSON parsing.

**Before:** There was no repository helper for this PC-hosted public-link workflow. An improvised tunnel command could target the wrong port, retain login traffic in the default inspector or make the operator think that an unhealthy application was ready.

**After:** The script performs these specific operations:

| Operation in the actual script | Result and reason |
| --- | --- |
| Parameter validation | `Port` accepts 1024–65535 and explicitly rejects the known PostgreSQL ports 5432, 55432 and 55433. Optional `PublicDomain` accepts only a hostname matching the assigned Free `ngrok-free.app`/`.dev` pattern, without a scheme, path or port. |
| Executable resolution | Resolves an explicit path, then `ngrok.exe` on PATH, then the ignored `.local/tools/ngrok.exe` fallback. It requires an existing `.exe`; it does not download a binary or install a service. |
| Loopback origin | Builds `http://127.0.0.1:<Port>` itself. It does not accept an arbitrary upstream URL or expose PostgreSQL. |
| Health precheck | Uses `Invoke-WebRequest` with a five-second timeout and no redirect following. It requires HTTP 200 and JSON `status` exactly `UP` from `/actuator/health` before starting ngrok. |
| `ConvertTo-HealthJsonText` | Accepts either a string or a UTF-8 byte array from PowerShell's web-response Content, rejects unsupported types/invalid UTF-8, and removes a leading byte-order mark before JSON parsing. This supports the Actuator vendor JSON media type without treating individual bytes as JSON text. |
| Cookie security preflight | Makes an anonymous `/api/auth/csrf` request, without supplying an account/password. `Test-SecureSessionCookie` requires a valid JSESSIONID cookie with Secure, HttpOnly and exactly one SameSite=Lax attribute. It handles coalesced Windows headers without splitting an Expires-date comma and rejects insecure duplicates, empty values, ambiguity and newline injection. Cookie/token values are not printed. |
| Preflight-only mode | `-PreflightOnly` performs local health/cookie checks and returns before running ngrok or asking for credentials. |
| Agent arguments | Builds `@('http', $origin, '--inspect=false')`; an explicitly supplied assigned domain adds `--url https://<domain>`. The default lets the agent use the account's assigned development domain. |
| Foreground launch | Runs `& $NgrokPath @ngrokArguments`. The terminal stays attached; Ctrl+C stops sharing. A nonzero `$LASTEXITCODE` becomes an actionable script error. |
| Credential handling | Does not accept, open, print, save or place an authtoken on the command line. The owner configures the account privately. No captured HTTP request is replayed. |

The health check and cookie preflight establish that the selected local HTTP application is up and issues the required cookie flags. They do not prove public TLS, the real forwarded chain, browser session behavior or remote login. Those checks remain part of actual public startup verification. The preflight creates one anonymous CSRF session but does not create or authenticate a user.

**Why the health decoder was added:** A valid JSON response does not guarantee that a PowerShell web-response object exposes Content as a string. Independent inspection of PowerShell 7.6.5's actual response-content helper confirmed that Actuator's `application/vnd.spring-boot.actuator.v3+json` can be classified as non-text/non-ordinary-JSON and exposed as `System.Byte[]`. The decoder converts that representation once using strict UTF-8, then parses the complete JSON text. The compatibility check against that response-content helper parsed UP without making an HTTP request. The later real preflight pass is separate runtime evidence. Health failures now identify only the request/decode/parse/status stage, exception type and numeric HTTP code; they do not print response bodies, headers, tokens or exception messages. This correction is distinct from the separately confirmed native-memory startup failure described below.

**Why inspection is disabled:** ngrok's normal HTTP inspector can display request headers, bodies and responses, including login traffic. This helper always supplies `--inspect=false` to avoid retaining that local inspection view. ngrok remains a provider in the request path and terminates public HTTPS at its edge; disabling inspection does not remove that trust relationship. [Official CLI flags](https://ngrok.com/docs/gateway/agent/cli), [HTTP inspection behavior](https://ngrok.com/docs/gateway/agent/web-inspection-interface)

The script makes no firewall/router change, requests no administrator elevation, starts no background service and deletes no data. Stopping the tunnel ends public access without deleting accounts or reservations in the selected local database.

### WEB-015C — Test the actual Tomcat forwarding boundary

**New file/class:** `src/test/java/com/evcharging/security/TunnelProxyBoundaryTest.java` / `TunnelProxyBoundaryTest`.

The test class loads the checked-in tunnel profile, creates the actual Tomcat `RemoteIpValve` and observes its output. It does not substitute a custom forwarded-header parser. Its eight test methods cover:

| Actual test method | Behavior checked |
| --- | --- |
| `loopbackAgentSuppliesSeparateClientAddressesAndHttps` | Distinct internet visitors remain distinct when the loopback agent forwards them; HTTPS is recognized. |
| `ipv6LoopbackAgentCanSupplyAnIpv6Visitor` | IPv6 loopback and an IPv6 visitor work. |
| `lanAndPublicPeersCannotForgeClientIdentityOrHttps` | Direct LAN/private/public peers cannot use forwarding headers as trusted identity/protocol. |
| `appendedActualVisitorWinsOverForgedLeftmostPrefix` | The appended real visitor is chosen instead of a forged earlier address. |
| `privateForwardedAddressStopsTraversalInsteadOfTrustingTheLan` | A private forwarded address is not silently treated as an additional trusted proxy. |
| `forwardedHttpCannotBeUpgradedByAnEarlierHttpsToken` | HTTP or `https, http` cannot claim secure transport. |
| `mixedProtocolChainIsConservativeAndAllHttpsChainIsSecure` | Mixed chains remain conservative; an all-HTTPS chain is recognized. |
| `tunnelProfileKeepsSecureCookiesAndDoesNotChangeDatabaseOrNormalLocalMode` | Loopback/Secure settings hold, no database/Flyway/admin/demo override is added, and existing base/local/production settings remain intact. |

The `throughValve` helper constructs the request, applies profile values to the valve and records the resulting address/scheme/security state. The `properties` helper reads the actual classpath configuration and fails if it is missing. Neither helper needs a real account, token or live tunnel.

**Executed Java verification:** The final full `clean verify` completed on 18 October 2026 at **15:33:55 IST** in **3 minutes 22 seconds**: **148 tests executed, 148 passed, zero failures, errors or skips; BUILD SUCCESS**. It packaged the runnable Spring Boot JAR. The log is `work/pc-tunnel-verify-retry.log`. This was the default isolated H2 test configuration, including the actual embedded-Tomcat HTTP test; it is not 148 separate PostgreSQL integration tests or proof of a live tunnel.

The first PC-tunnel run completed at **13:22:54 IST** after 1 minute 31 seconds with **148 executed, zero failures, one error and zero skips; BUILD FAILURE**. `SessionCapacityHttpIntegrationTest` could not connect to its loopback test server: `java.net.SocketException: Permission denied: connect`. That environment/network-permission failure remains recorded in `work/pc-tunnel-verify.log`; it is not counted as a successful run. The completed retry above is the successful evidence, with no test removed or skipped.

### WEB-015D — A prepared-workspace launcher for the owner

**User-facing artifact outside the Git repository:** `outputs/Start-PC-Public-Website.ps1`, relative to the task workspace. This is a newly created owner-run file, not a Java class or generic installer. It is separate from the repository-only Git inventory in Section 13. Its parameters are `Port`, `PublicDomain` and `ApplicationOnly`; PublicDomain defaults to the account's observed assigned hostname, `unbaked-skyrocket-abide.ngrok-free.dev`, and is passed explicitly to the tunnel helper. Pinning this hostname is not proof that an endpoint is running.

The owner launcher connects the already prepared local database, packaged Java application and repository tunnel helper. It does not depend on keeping Codex open, but the prepared workspace/runtime files must remain in place. A source ZIP alone is not the same as this prepared workspace. It downloads no software and creates no database or default administrator.

| Actual function or block | What it does and why |
| --- | --- |
| `Test-LoopbackPortAvailable` | Temporarily binds the requested loopback port, then closes that listener. An occupied application port causes refusal; no existing IDE/application process is silently reused or stopped. |
| `ConvertTo-WindowsArgument` | Applies Win32 quoting rules for spaces, quotes and trailing backslashes. This lets Windows PowerShell 5.1 pass the intended native arguments instead of misreading a path or placeholder. |
| `Get-NormalizedPath` | Canonicalizes directory/executable paths for identity comparisons rather than relying on a loose text match. |
| Required-file and PostgreSQL checks | Requires the prepared Java/JAR/ngrok/helper files, PostgreSQL 17.11 binaries, PG_VERSION 17 and matching private workspace metadata. It targets the reviewed workspace cluster on loopback 55433 and database `evcharging`, not the older `EVCHARGING` installation on another server. |
| Cluster status/start and identity query | Starts the exact prepared directory only when the port is free and `pg_ctl` explicitly reports stopped (code 3). If a listener already exists, the authenticated read-only SQL identity check is mandatory regardless of `pg_ctl`'s status. That query checks database, user, port, version, listen address and real data directory. A mismatched identity fails; no foreign listener is stopped or silently trusted, and no row dump, database creation or reset is performed. |
| Controlled child configuration | Temporarily clears inherited cloud/IDE/test overrides, pins the reviewed datasource/Flyway identity, uses `local,tunnel`, binds 127.0.0.1, validates migrations/schema and disables automatic baselining. Administrator bootstrap is blank; APP_DEMO_MODE is false. Password values stay in the child environment and are referenced through literal Spring placeholders in arguments. |
| Owned Java startup and readiness | Starts Java hidden with bounded memory/thread/pool settings, records PID/start time/executable/JAR hash and waits for that process's own Tomcat-started log plus health UP. It then invokes the tunnel helper's Secure-cookie `-PreflightOnly` before requesting an ngrok token. |
| `ApplicationOnly` branch | Runs the local application/preflight without asking for a token or creating a public endpoint. This supports local runtime verification but does not establish a working public link. |
| Private owner token prompt | After readiness/preflight, `Read-Host -AsSecureString` receives the token privately. It is supplied to the foreground agent through NGROK_AUTHTOKEN, not source, a saved project token file or command-line text; Java is started before this token is supplied. |
| `finally` cleanup | Checks the recorded Java PID, start time and executable before stopping only the process it created. PostgreSQL/data are left intact. Controlled process environment values are restored and temporary secret references are cleared. A forced terminal kill or power loss can bypass ordinary cleanup; inspect the recorded local process/log details if needed. |

The owner can run the sidecar after retaining the prepared workspace. No actual authtoken, session cookie, password or CSRF token is reproduced in these instructions. Successful parser/static tests are distinct from a completed application/tunnel launch.

### WEB-015E — Explain the owner's startup, fixed domain and Free limits

**New file:** `docs/PC_HOSTED_PUBLIC_LINK_GUIDE.md`.

The guide contains the architecture, local database/profile prerequisites, official Windows agent installation, private owner account/token handoff, launch/share/stop steps, proxy/cookie explanation, verification checklist and troubleshooting. It does not invent a working hostname or a known administrator password. Its examples keep tokens out of chat, source and command-line arguments, and restore temporary process environment settings after use.

**Modified file:** `README.md`.

The main instructions now identify PC-hosted ngrok as the current choice, link the new guide and retain Render material explicitly as earlier reference. They distinguish public HTTPS tunnel operation from ordinary local-only HTTP and retain the existing-data preservation requirement.

The key launch command, after the owner privately installs/authenticates the agent and starts the reviewed app/database, is:

```powershell
.\ops\Start-PublicTunnel.ps1 -Port 18082
```

The guide documents one account-assigned Free development domain, reusable across agent restarts while the account retains it. This is not a newly purchased custom domain and not a promise of permanent availability. The agent must print the actual address; an address being assigned is not evidence of a tested application.

The official Free limits checked for the guide include **20,000 HTTP requests and 1 GB outbound transfer per month**, plus request-rate/endpoint limits. A page visit can fetch multiple assets and API calls, so 20,000 requests does not mean 20,000 independent visitors. The Free HTML interstitial requires the visitor to choose **Visit Site**; its domain cookie then suppresses the warning for seven days. This is the provider's warning page, not an inactive application control. A response header added to Spring cannot remove a warning shown before the application is reached. No paid bypass or subscription is introduced. [Official Free-plan limits](https://ngrok.com/docs/pricing-limits/free-plan-limits), [assigned-domain explanation](https://ngrok.com/blog/free-static-domains-ngrok-users)

### Files created and modified for WEB-015

| File | Action | Reason | Change |
| --- | --- | --- | --- |
| `src/main/resources/application-tunnel.properties` | Created | Loopback-only trusted tunnel profile and Secure cookies; no database/business-rule override. | WEB-015 |
| `src/test/java/com/evcharging/security/TunnelProxyBoundaryTest.java` | Created | Eight regressions against the real Tomcat valve and profile. | WEB-015 |
| `ops/Start-PublicTunnel.ps1` | Created | Validated foreground HTTP tunnel with health precheck and inspection disabled. | WEB-015 |
| `docs/PC_HOSTED_PUBLIC_LINK_GUIDE.md` | Created | Clear Windows setup, private handoff, fixed Free link, limits and verification. | WEB-015 |
| `README.md` | Modified | Current PC-hosted instructions and historical Render distinction. | WEB-009/013/015 |
| `docs/WEBSITE_COMPLETION_CHANGE_REPORT.md` | Modified | Records the current choice, exact changes, cancellation action and real evidence boundaries. | WEB-015 |
| `outputs/Start-PC-Public-Website.ps1` (workspace artifact outside repository) | Created | Start only the reviewed workspace database/Java, verify Secure cookie flags and request the owner's token privately before the pinned-domain tunnel. | WEB-015 |

### What was checked, and what remains to be verified

On **20 October**, the current `work/test-pc-launcher-static.ps1` was executed using both Windows PowerShell **5.1.26100.9711** and PowerShell **7.6.5**. Both passed with exit code zero: two script ASTs, 15 synthetic cookie cases, an actual WebHeaderCollection representation, **seven health-content cases**, nine native argument round-trips through Windows CommandLineToArgvW, and **six listener-status decisions**. Health cases cover strings/UTF-8 bytes with or without a leading byte-order mark, and rejection of null, unsupported types and invalid UTF-8. Listener cases combine a free/busy port with status 0/3/4, evaluating actual branch conditions without executing startup bodies. The script extracts only pure functions; it does not dot-source the complete launcher, run Java/ngrok/psql, connect to a database, request credentials or make HTTP calls. The operations agent also recorded zero syntax errors for the five guide PowerShell examples. These results supplement the 18 October static checks and are not a live public tunnel.

```powershell
& 'C:/WINDOWS/System32/WindowsPowerShell/v1.0/powershell.exe' -NoProfile -ExecutionPolicy Bypass -File 'work/test-pc-launcher-static.ps1'
& 'C:/Users/kshit/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe' -NoProfile -File 'work/test-pc-launcher-static.ps1'
```

The completed Maven command below was run from the repository root. The JDK/cache/temp paths select the prepared verification tools; no database password is included:

```powershell
$env:JAVA_HOME='C:/Users/kshit/Documents/Codex/2026-10-17/codex-master-prompt-ev-charging-spring/work/runtime/jdk17/jdk-17.0.20.1+1'
$env:MAVEN_USER_HOME='C:/Users/kshit/Documents/Codex/2026-10-17/codex-master-prompt-ev-charging-spring/work/maven'
$env:TEMP='C:/Users/kshit/Documents/Codex/2026-10-17/codex-master-prompt-ev-charging-spring/work/tmp'
$env:TMP=$env:TEMP
$env:MAVEN_OPTS='-Xmx160m -XX:ReservedCodeCacheSize=48m -XX:ActiveProcessorCount=2'
.\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" '-DargLine=-Xmx384m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2' clean verify
```

The successful 148-case result is recorded above. The earlier 140-case H2/PostgreSQL and 17-case frontend results remain evidence for their earlier revisions; this new Java-only configuration/test change does not fabricate a new 148-case PostgreSQL run or a public-browser success. Two browsers behind the same router do not prove that different public client IPs receive independent limits.

<!-- PC_RUNTIME_EVIDENCE_START -->

| Runtime checkpoint | Actual state at this report update |
| --- | --- |
| Assigned account hostname | Read-only account listing showed `unbaked-skyrocket-abide.ngrok-free.dev`; no active endpoint was inferred from that listing. |
| First owner-launcher native attempt | Incomplete: `pg_ctl` process-visibility handling blocked the launch although the prepared PostgreSQL server was ready (PID 22036). It was not counted as a successful application-only/public run. |
| Earlier incomplete native attempt | The authenticated PostgreSQL identity check passed. Owned Java PID 26464 loaded `local,tunnel`, reached loopback 18082 with PostgreSQL 17.11/schema version 2 and started in about 31.8 seconds. The helper then failed its health preflight; cleanup stopped its own Java process. That incomplete attempt was not counted as a successful owner/public workflow. |
| Separate 18 October startup failure | The attempt recorded under `work/pc-public-runtime/20261018-154113-31196` failed during early JVM startup with a confirmed native-memory allocation failure for Java PID 30896. It is distinct from the PowerShell health-content compatibility issue and from the earlier JUnit loopback-permission error. It was not a successful runtime test. |
| Successful owner-launcher application/preflight, 20 October | `outputs/Start-PC-Public-Website.ps1 -ApplicationOnly` completed local readiness/preflight at **10:57:33 IST**. The authenticated PostgreSQL 17.11 identity check passed; owned Java PID 24288 used `local,tunnel`, 127.0.0.1:18082 and the reviewed 127.0.0.1:55433/evcharging database. Boot startup took **31.825 seconds**. Flyway V1/V2 were already current at schema version 2; no migration was needed. Actual helper health and Secure/HttpOnly/SameSite=Lax cookie checks passed. No token was requested and no tunnel was started. Logs are under `work/pc-public-runtime/20261020-105647-24332`. |
| Current mode and controlled stop | A separate read-only `/api/site-info` check returned `demo:false` and an empty notice. Ctrl+C ended the application-only verification session; application port 18082 was then confirmed free. The prepared database/data were retained. This test process is not being described as a continuously running public service. |
| Account token and public endpoint | Not configured/verified at this checkpoint. Owner must complete private token entry; no token is requested in this report or chat. |
| Actual public probe, 20 October | A read-only HTTPS GET of the assigned domain's `/actuator/health`, with the automation warning-page request header, returned HTTP **404** and provider code **ERR_NGROK_3200**; it did not reach an application health UP response. The public endpoint was offline at this check. No credentials or application mutations were sent. This provider result is not relabeled as an application health pass or proof that the locally verified backend is unhealthy. |
| Public HTTPS/HTTP and forwarded clients | Pending live checks of certificate, HTTP redirect/unavailability and actual trusted agent/header behavior. |
| Public user journey | Pending ngrok registration/login/refresh, booking/cancel, administrator completion, returned capacity, profile, inbox and permission checks. Historical local/Render results are not relabeled as ngrok results. |

<!-- PC_RUNTIME_EVIDENCE_END -->

The local owner-launcher and security preflight are verified. **The latest public probe confirmed the ngrok endpoint offline; public connectivity and the public user journey remain incomplete.** Local success and an assigned hostname do not establish an active public site. The owner must run the full launcher, enter the account token privately in its hidden local prompt and retain the running window/PC/database/Java/agent/internet, then verify the HTTPS forwarding address. The token must not be sent in chat. Existing local `EVCHARGING` preservation/migration remains pending; neither suspension of Render nor starting a tunnel completes it. Online payments, physical charger control, scheduled appointments, account recovery and external messages remain the previously documented separate product work.

The next usable public address is the owner's **verified ngrok HTTPS forwarding address**. The earlier Render URL is historical and its web service is suspended. Public availability now follows the PC/app/database/tunnel lifecycle and the provider's Free limits.
