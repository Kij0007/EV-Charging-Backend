# EV Charging Backend — Engineering Change Report

Date: 17 October 2026

Repository: https://github.com/Kij0007/EV-Charging-Backend

Baseline: 47872bf6572b01058a5cf75e3c3ba098a56d09f4

Development branch: codex/backend-audit-improvements

This report describes actual source changes relative to the baseline. “Before” snippets were retrieved with git show 47872bf:path; “After” snippets come from the changed source. Focused snippets omit surrounding code. New functionality is explicitly identified as absent before this task. No original credential value is reproduced.

Verification references identify concrete automated tests. Their actual execution environment, commands, observed counts and limitations are recorded in Section 9. An implemented check and a successful test execution are different kinds of evidence.

## 1. Executive Summary

The original Java 17 / Spring Boot 3.5.3 project already supported user registration/retrieval/listing; station creation/retrieval/listing/availability/manual reduction; and booking creation/retrieval/listing/cancellation. It used Spring MVC, Bean Validation, Spring Data JPA, PostgreSQL, constructor injection and booking response DTOs. These appropriate foundations were retained.

The station model tracks aggregate inventory through totalSlots and avaialableSlots. It has no start/end times, connector identity, duration or completion policy. The changes protect aggregate capacity; they do not claim to prevent overlapping time reservations in a model without time reservations.

Confirmed issues included a committed database password, unprotected booking check-then-write operations, incomplete transaction boundaries, repeated cancellation releasing capacity again, entity input/output exposure, missing validation/uniqueness, inconsistent errors, misleading demonstration integrations and a context test outside the application package.

Implemented changes add transactional row locking, idempotent cancellation, optional booking replay keys, validated DTOs, safe user/station update/delete, station search and booking filters. PostgreSQL, Java 17 and Boot 3.5.3 remain. H2 is test-only. No database migration was run against existing data.

RazorpayGateway and StripeGateway remain explicitly labeled development mocks. EmailNotificationSender and SmsNotificationSender remain mocks. No real payment, verification, refund, external message or paid service is required. Notifications now run after a committed booking and cannot undo it on a sender runtime failure.

The API remains anonymous and is intended for private local learning. It defaults to loopback binding. Authentication/authorization were not invented without roles and ownership requirements; the production profile does not add them. Public deployment requires the security work in Section 11. Existing databases require the reviewed manual migration in Section 7. Execution evidence and environment blockers are reported in Section 9.

## 2. Original Architecture

### 2.1 Actual classes and responsibilities

| Original class/file | Responsibility and original behavior | Decision |
|---|---|---|
| DemoApplication | Boot bootstrap in com.evcharging; application discovery. | Retained. |
| pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties | Boot 3.5.3, Java 17, Maven 3.9.14 wrapper, MVC/validation/JPA/PostgreSQL/Lombok/DevTools/Actuator/test dependencies. PostgreSQL appeared twice. | Preserve versions/wrapper; remove duplicate; add test-only H2 and release 17. |
| AppConfig | Selected PaymentGateway and NotificationSender using property strings; unknown values silently fell back. | Normalize/validate selection and reject unsupported real modes. |
| UserController | POST /users/reg, GET /users/{id}, GET /users/all; accepted/returned User entities. | Request/response DTOs; preserve original paths and POST response; add update/delete. |
| UserService; UserServiceImpl; UserRepository | Save registration, lookup with UserNotFoundException, list all through JpaRepository. | Add normalization, uniqueness, locks and safe lifecycle operations. |
| ChargingStationController | Create/list/get/availability/manual reduction. | Validate, return DTOs, correct domain errors; add update/delete/search. |
| StationService; StationServiceImpl; ChargingStationRepository | Station persistence and unprotected inventory decrement. | Preserve aggregate model; lock writes and preserve occupied capacity. |
| BookingController | Create returned empty 200; get/list returned BookingResponseDTO; cancellation used PUT /booking/cancel?id=... | Return created DTO/Location; preserve cancellation; add filters/replay header. |
| BookingService; BookingServiceImpl; BookingRepository | Read user/station through services, check availability, mock payment of 500, save, decrement, notify; no enclosing transaction. Repeated cancel always incremented. | Transactional lifecycle, locks, state checks, DTO mapping, fetch plans, replay key and event. |
| User | users table, id/name/email, inverse bookings list; uninitialized list and JSON relationship annotation. | Initialize list, constrain columns, use DTOs; no booking cascade deletion. |
| ChargingStation | Charging_Stations table, name/location/total/available inventory; existing avaialableSlots typo. | Preserve spelling; add nullability, lengths and inventory check. |
| Booking | Booking table, user/station FKs, String status, double amount; user eager by default, station lazy. | Lazy relationships, status constants/check, request key, indexes; preserve values. |
| BookingRequestDTO | Required IDs and positive amount; IDs were not positive-validated. Amount was already ignored in service. | Validate IDs; document compatibility amount. |
| BookingResponseDTO | bookingId/userId/stationId/bookingStatus/amount. | Retain class and response fields. |
| PaymentService; PaymentServiceImpl; PaymentGateway | Delegated double amount, printed processing text. | Finite positive validation and sanitized failure boundary; retain interfaces. |
| RazorpayGateway; StripeGateway | Printed success and returned true; no provider calls. | Explicit mocks; remove misleading payment claims. |
| NotificationService; NotificationServiceImpl; NotificationSender | Delegated confirmation String. | Retain strategy interfaces; invoke after commit. |
| EmailNotificationSender; SmsNotificationSender | Printed message as “sent”; no recipient or provider. | Explicit mock logs without contents. |
| UserNotFoundException; BookingNotFoundException | Runtime not-found exceptions. | Retained; add missing station/request/conflict/payment types. |
| GlobalExceptionHandler | String 404 responses and separate validation maps. | Unified safe error object, including MVC/database failures. |
| application.properties | PostgreSQL literal credential, automatic schema update, SQL logging and irrelevant H2 console flags. | External configuration and explicit dev/prod policies. |
| DemoApplicationTests in com.example.demo | One contextLoads test outside Boot discovery package; no isolated datasource. | Relocate purpose-preserving context test; add test profile and behavior tests. |
| .gitignore; pom.properties | Ignored env/build/editor files; .env.example also ignored. pom.properties repeated artifact metadata. | Allow safe template; retain unrelated metadata file. |

The problem was not insufficient layers. Several repository operations representing one business action did not share one transaction and concurrency rule.

### 2.2 Original dependency flow

~~~mermaid
flowchart TD
    Client[HTTP client] --> UC[UserController]
    Client --> SC[ChargingStationController]
    Client --> BC[BookingController]
    UC --> US[UserService / UserServiceImpl]
    SC --> SS[StationService / StationServiceImpl]
    BC --> BS[BookingService / BookingServiceImpl]
    BS --> US
    BS --> SS
    BS --> PS[PaymentService / PaymentServiceImpl]
    BS --> NS[NotificationService / NotificationServiceImpl]
    US --> UR[UserRepository]
    SS --> SR[ChargingStationRepository]
    BS --> BR[BookingRepository]
    UR --> DB[(PostgreSQL: users, charging_stations, booking)]
    SR --> DB
    BR --> DB
    AC[AppConfig] --> PG[PaymentGateway: Stripe/Razorpay mocks]
    AC --> NT[NotificationSender: email/SMS mocks]
    PS --> PG
    NS --> NT
    UC --> UE[User entity JSON with bookings]
    SC --> SE[ChargingStation entity JSON]
    BC --> BD[BookingResponseDTO on reads]
~~~

## 3. Problems Discovered

“Verified” here means the cited condition exists in the original source, not that an exploit occurred or historical data is known to be corrupt. Paths in this table are relative to src/main/java/com/evcharging unless an explicit root path is shown.

| ID | Severity | Affected file/class/method | Original evidence | Impact / reason to fix |
|---|---|---|---|---|
| SEC-01 | P0 | src/main/resources/application.properties | Literal datasource password in the baseline commit; value omitted. | Working-tree removal does not remove Git history. Rotate and remediate history. |
| SEC-02 | P1 | pom.xml managed dependencies; application.properties | Boot 3.5.3 manages Tomcat 10.1.42; no upload API needs default multipart parsing. Official advisory CVE-2025-61795 includes this Tomcat version. | Disable unused parser now; dependency patch review remains required before broader deployment. See CHG-018 and Section 10. |
| BK-01 | P1 | service/impl/BookingServiceImpl.java, createBooking; StationServiceImpl.reduceAvailableSlot | Availability checked before separate save/decrement; no enclosing transaction or lock. | Concurrent over-reservation and partial state after failure. |
| BK-02 | P1 | BookingServiceImpl.cancelBooking | Every call sets Cancelled and increments capacity, then saves only Booking. | Retries create capacity; station persistence depends on session/transaction circumstances. |
| BK-03 | P2 | BookingController.createBooking; BookingRepository | No request identity and empty create response. | Retrying an ambiguous request can reserve twice. |
| API-01 | P1 | controller/UserController.java; ChargingStationController.java | @RequestBody entity; entity response. | Persistence IDs/nested fields writable; graph/proxy exposure; validation missing. |
| USR-01 | P1 | entity/User.java; UserServiceImpl.registerUser | List uninitialized; save only; no email uniqueness. | New-object helper can fail; duplicate identities accepted. |
| ST-01 | P1 | entity/ChargingStation.java; StationServiceImpl.addStation | Counter fields lack validation/checks. | Negative/zero total or available > total invalidates inventory. |
| ST-02 | P1 | StationServiceImpl.getStationById; ChargingStationController.checkAvailabilitySlot | Generic RuntimeException for missing/full station. | Normal domain outcomes become 500. |
| INT-01 | P1 | payment/*Gateway.java; notification/*NotificationSender.java | Always-success mocks printed real-sounding success statements. | Demonstrations can be mistaken for external integrations. |
| INT-02 | P1 | BookingServiceImpl.createBooking | Notification invoked directly during write sequence. | Sender failure can fail an already partially written operation; rollback may follow premature notification. |
| CFG-01 | P2 | config/AppConfig.java | Unknown selections silently fall back to Stripe/SMS. | Misconfiguration selects unintended strategy; no real/mock distinction. |
| ERR-01 | P2 | global/exception/handler/GlobalExceptionHandler.java | String/map errors and missing station/conflict/database handlers. | Unstable error contract, unsuitable domain statuses. |
| DB-01 | P2 | entity/Booking.java; repositories; entity responses | Eager user, lazy station, implicit list fetch behavior and graph serialization. | Avoidable fetching and coupling to an open session. |
| TEST-01 | P1 | src/test/java/com/example/demo/DemoApplicationTests.java | Package outside com.evcharging. | Default Boot configuration discovery fails; no lifecycle/concurrency coverage. |
| CFG-02 | P2 | pom.xml; application.properties; .gitignore | Duplicate driver, update/show-sql defaults, H2 console flags, example env ignored. | Ambiguous build and unsafe deployment habits. |
| CRUD-01 | P2 | UserService, StationService, BookingService | Update/delete/search/filter methods absent from actual interfaces. | Useful lifecycle operations incomplete; add only with history/capacity rules. |

Missing connector/time/login/refund requirements are roadmap boundaries, not fabricated defects in existing implementations.

## 4. Complete Change Log

Entries group coherent modifications; Section 5 maps every changed file to them. Small import/format/console cleanups are grouped with their behavior. Verification names identify checked-in tests; actual execution evidence is in Section 9.

### Change ID: CHG-001 — Safe runtime configuration and secrets

**Module:** Configuration/security.

**Files:** src/main/resources/application.properties; src/main/resources/application-dev.properties; src/main/resources/application-prod.properties; .env.example; .gitignore.

**Class/Method:** Spring external configuration, no Java method.

**Problem:** A credential was committed; automatic schema changes/SQL output were defaults.

**Before — actual nonsecret baseline lines:**
~~~properties
server.port=8081
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.h2.console.enabled=true
~~~

The password line is deliberately excluded, not reconstructed.

**After — application.properties:**
~~~properties
server.address=${SERVER_ADDRESS:127.0.0.1}
server.port=${SERVER_PORT:8081}
spring.datasource.password=${DB_PASSWORD:}
spring.jpa.hibernate.ddl-auto=${DB_DDL_AUTO:validate}
spring.jpa.open-in-view=false
spring.jpa.show-sql=false
server.error.include-stacktrace=never
~~~

**What changed:** Credentials resolve environment variables; dev opts into update for isolated data; prod validates; H2 console settings removed; safe example allowed by .gitignore. Strict unknown-field JSON handling rejects undeclared request properties.

**Why it changed:** Secrets belong outside source control. Existing schemas need reviewed migration. Undeclared input must not silently bypass DTO intent.

**How it works internally:** Spring Environment resolves placeholders; profile properties overlay base settings. Spring does not automatically load .env. Disabling open-in-view requires mapping data within service transactions.

**Impact:** Reproducible credential-free source and safer private defaults.

**Trade-offs:** Fresh data needs the dev profile or manual schema. validate does not check every constraint/index. Loopback is not authentication and can be overridden. Rotation/history remediation remain manual.

**Verification:** DemoApplicationTests.contextLoads; BookingIntegrationTest.realMvcRejectsMassAssignmentAndMapsResponsesWithoutOpenSession; actual runtime evidence in Section 9.

### Change ID: CHG-002 — Java target and isolated test foundation

**Module:** Build/testing.

**Files:** pom.xml; src/test/java/com/example/demo/DemoApplicationTests.java (deleted location); src/test/java/com/evcharging/DemoApplicationTests.java; src/test/resources/application-test.properties.

**Class/Method:** DemoApplicationTests.contextLoads; Maven compiler/test runtime.

**Problem:** Context test discovery was incorrect; PostgreSQL setup was required; duplicate driver declaration.

**Before:**
~~~java
package com.example.demo;
~~~
~~~java
@SpringBootTest
class DemoApplicationTests {
~~~

**After:**
~~~java
package com.evcharging;
~~~
~~~java
@SpringBootTest
@ActiveProfiles("test")
class DemoApplicationTests {
~~~
~~~xml
<maven.compiler.release>17</maven.compiler.release>
~~~

**What changed:** Relocated context test; test-only H2 and in-memory create-drop profile; removed duplicate PostgreSQL dependency; explicit release 17. Retained Java/Boot/wrapper versions.

**Why it changed:** Tests must discover the intended application without using persistent credentials/data.

**How it works internally:** Boot searches upward from a test package for application configuration. The profile replaces datasource/schema settings only in tests. Compiler release controls API/class target, not the JVM actually executing Maven.

**Impact:** Repeatable isolated tests; original context-test purpose retained.

**Trade-offs:** H2 PostgreSQL mode is not PostgreSQL and cannot prove production lock/dialect behavior. Running on a newer JDK alone would not prove Java 17 runtime compatibility; Section 9 distinguishes environments.

**Verification:** DemoApplicationTests.contextLoads; exact wrapper executions in Section 9.

### Change ID: CHG-003 — User HTTP DTO boundary

**Module:** User management/MVC.

**Files:** src/main/java/com/evcharging/controller/UserController.java; src/main/java/com/evcharging/dto/UserRequestDTO.java; src/main/java/com/evcharging/dto/UserResponseDTO.java.

**Class/Method:** registerUser/getUserById/getAllUsers; UserRequestDTO.toEntity; UserResponseDTO.from.

**Problem:** User entity binding exposed persistence fields and graph serialization.

**Before — UserController:**
~~~java
public String reisterUser(@RequestBody User user)
{
    userService.registerUser(user);
~~~
~~~java
public User getUserById(@PathVariable Long id)
{
    return userService.getUserById(id);
}
~~~

**After:**
~~~java
public String registerUser(@Valid @RequestBody UserRequestDTO request) {
    userService.registerUser(request.toEntity());
    return "User Registered Successfully";
}
~~~
~~~java
public UserResponseDTO getUserById(@PathVariable Long id) {
    return UserResponseDTO.from(userService.getUserById(id));
}
~~~

**What changed:** Only name/email are writable; nonblank/length/email validation; trim/lowercase before validation; response exactly id/name/email. Preserve POST status/text.

**Why it changed:** Database IDs/relationships should not be writable request fields or automatically serialized responses.

**How it works internally:** Jackson constructs the record; @Valid runs Bean Validation; the controller then creates a new User. Response mapping does not touch bookings. Global unknown-field rejection rejects id/bookings.

**Impact:** 400 for invalid input and no mass assignment or booking graph exposure.

**Trade-offs:** Clients sending extras or using nested booking responses must adapt; this is a documented contract change.

**Verification:** UserControllerTest.registrationRetainsExistingSuccessContractAndNormalizesRequest; invalidRegistrationReturnsFieldErrorsWithoutCallingService; requestCannotSupplyPersistenceIdentity; userReadReturnsBoundedDtoWithoutBookingGraph; userListUsesSameResponseContract; BookingIntegrationTest.realMvcRejectsMassAssignmentAndMapsResponsesWithoutOpenSession.

### Change ID: CHG-004 — User identity uniqueness and entity lifecycle

**Module:** User persistence.

**Files:** src/main/java/com/evcharging/entity/User.java; src/main/java/com/evcharging/repository/UserRepository.java; src/main/java/com/evcharging/service/impl/UserServiceImpl.java; src/main/java/com/evcharging/dto/UserRequestDTO.java.

**Class/Method:** registerUser/normalize; User.addBooking; uniqueness queries.

**Problem:** Arbitrary registrations and duplicate emails were saved; a new User had a null bookings collection.

**Before:**
~~~java
userRepository.save(user);
System.out.println("Registration completed succesfully");
~~~
~~~java
private List<Booking> bookings;
~~~

**After:**
~~~java
normalize(user);
if (userRepository.existsByEmailIgnoreCase(user.getEmail())) {
    throw new DomainConflictException("A user with this email already exists");
}
userRepository.saveAndFlush(user);
~~~
~~~java
private List<Booking> bookings = new ArrayList<>();
~~~

**What changed:** Strip name/email; lowercase Locale.ROOT; bound service values; case-insensitive existence check plus uk_users_email database uniqueness; nonnull lengths 100/254; transactional saveAndFlush; initialize collection. Remove entity JSON relationship annotations because DTOs own serialization.

**Why it changed:** An existence check alone races; the database must arbitrate concurrent duplicates. A new object needs usable collection invariants.

**How it works internally:** Flush issues SQL before return. Concurrent normalized duplicates meet the unique constraint and roll back; advice maps database conflicts to sanitized 409. addBooking can now update both sides without null failure.

**Impact:** Consistent new email identity and functional new-object helper.

**Trade-offs:** Existing dirty data needs reviewed normalization. The unique constraint is on stored normalized email, not a lower(email) expression; direct SQL writers can bypass normalization.

**Verification:** UserServiceImplTest.registrationNormalizesEmailBeforeCheckingAndSaving; duplicateEmailCannotBeRegistered; newUserCanAddBookingWithoutUninitializedCollection; BookingIntegrationTest.databaseRejectsInvalidCapacityAndDuplicateNormalizedEmail.

### Change ID: CHG-005 — Safe user update/deletion

**Module:** User lifecycle.

**Files:** src/main/java/com/evcharging/service/UserService.java; src/main/java/com/evcharging/service/impl/UserServiceImpl.java; src/main/java/com/evcharging/repository/UserRepository.java; src/main/java/com/evcharging/controller/UserController.java.

**Class/Method:** updateUser; deleteUser; findByIdForUpdate.

**Problem:** Update/delete were absent; naive deletion would lose or conflict with referenced history and race booking creation.

**Before:** No corresponding baseline method/endpoint existed; no predecessor snippet is invented.

**After — deleteUser:**
~~~java
User user = userRepository.findByIdForUpdate(id)
        .orElseThrow(() -> new UserNotFoundException("User not found with ID : " + id));
if (bookingRepository.existsByUserId(id)) {
    throw new DomainConflictException("User has booking history and cannot be deleted");
}
userRepository.delete(user);
~~~

**What changed:** PUT /users/{id} retains identity and rejects another email owner; DELETE returns 204 or history conflict 409. Both lock the existing user. Any booking, including Cancelled, prevents deletion; no cascade added.

**Why it changed:** Booking history should not disappear as a side effect of account cleanup.

**How it works internally:** PESSIMISTIC_WRITE locks the user until transaction completion. Booking creation locks the same user first. Existence queries avoid loading full history.

**Impact:** Predictable 404/409/204 and preserved references.

**Trade-offs:** No soft deletion/anonymization policy; creates for one user serialize with edits. Historical personal-data retention remains a future requirement.

**Verification:** UserServiceImplTest.updateLocksExistingUserAndPreservesIdentity; updateRejectsAnotherUsersEmailBeforeMutating; deletionRefusesEvenHistoricalBookings; deletionWithoutBookingsSucceeds; UserControllerTest.updateReturnsUpdatedDto/deletionReturns204/deletionWithHistoryReturns409; BookingIntegrationTest.referencedUsersAndStationsCannotBeDeletedEvenAfterCancellation.

### Change ID: CHG-006 — Station validation and database inventory bounds

**Module:** Station creation/model.

**Files:** src/main/java/com/evcharging/entity/ChargingStation.java; src/main/java/com/evcharging/dto/StationRequestDTO.java; src/main/java/com/evcharging/dto/StationResponseDTO.java; src/main/java/com/evcharging/controller/ChargingStationController.java; src/main/java/com/evcharging/service/impl/StationServiceImpl.java.

**Class/Method:** registerStation; StationRequestDTO.isInventoryValid/toEntity; StationResponseDTO.from; addStation/normalizeDetails/validateInventory.

**Problem:** Blank details and impossible counters could be persisted; entity IDs were writable.

**Before — unconstrained fields:**
~~~java
private String stationName;
private String location;
private int totalSlots;
private int avaialableSlots;
~~~

**After — actual entity constraint:**
~~~java
@Check(constraints = "total_slots > 0 AND avaialable_slots >= 0 AND avaialable_slots <= total_slots")
~~~
**After — cross-field DTO check:**
~~~java
@AssertTrue(message = "Available slots must not exceed total slots")
public boolean isInventoryValid() {
    return totalSlots == null || avaialableSlots == null || avaialableSlots <= totalSlots;
}
~~~

**What changed:** DTO input/output; explicit nonnull positive total and nonnegative availability; cross-field bounds; nonblank trimmed name/location with lengths 150/255; service guards and nonnull/check columns. getId returns Long consistently with a generated nullable identifier. Preserve avaialableSlots spelling and POST response.

**Why it changed:** Request checks provide useful errors; database constraints protect all persistence paths.

**How it works internally:** @NotNull handles missing counters, @Min individual values, @AssertTrue their relationship. Hibernate can generate the CHECK on a fresh schema. Existing schemas need manual migration.

**Impact:** Invalid inventory fails before service or at database boundary.

**Trade-offs:** Both counters are explicitly required on create. Initial occupied capacity remains permitted; no historical bookings are invented for it. Schema validate alone cannot prove the CHECK exists.

**Verification:** StationServiceImplTest.invalidInventoryCannotBeCreated; ChargingStationControllerTest.inconsistentInventoryIsRejectedBeforeService/missingCapacityIsRejected/responsePreservesExistingAvailableSlotsSpelling; BookingIntegrationTest.databaseRejectsInvalidCapacityAndDuplicateNormalizedEmail.

### Change ID: CHG-007 — Shared station lock for reduction, resizing and deletion

**Module:** Station lifecycle/concurrency.

**Files:** src/main/java/com/evcharging/repository/ChargingStationRepository.java; src/main/java/com/evcharging/service/StationService.java; src/main/java/com/evcharging/service/impl/StationServiceImpl.java; src/main/java/com/evcharging/controller/ChargingStationController.java; src/main/java/com/evcharging/dto/StationUpdateRequestDTO.java.

**Class/Method:** findByIdForUpdate; reduceAvailableSlot; updateStation; deleteStation; lockedStation.

**Problem:** Read-modify-write decrement raced other mutations; an unrestricted update could reset reservations.

**Before:**
~~~java
ChargingStation station = getStationById(stationId);
~~~
~~~java
station.setAvaialableSlots(currentSlot - 1);
stationRepository.save(station);
~~~

**After — locked query:**
~~~java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from ChargingStation s where s.id = :id")
Optional<ChargingStation> findByIdForUpdate(@Param("id") Long id);
~~~
**After — preserve occupied count:**
~~~java
int occupied = station.getTotalSlots() - station.getAvaialableSlots();
if (request.totalSlots() < occupied) {
    throw new DomainConflictException("Total slots cannot be less than occupied slots");
}
~~~
~~~java
station.setTotalSlots(request.totalSlots());
station.setAvaialableSlots(request.totalSlots() - occupied);
~~~

**What changed:** Every inventory/metadata/delete mutation takes the transactional station lock. Add PUT/DELETE /station/{id}; update accepts stationName/location/totalSlots only; deletion refuses all booking history. Missing station becomes 404, full station 409; remove stdout messages.

**Why it changed:** Booking locking is ineffective if another writer can bypass the same lock.

**How it works internally:** Row locks serialize writers until commit/rollback. New available = new total − (old total − old available). Reducing below occupied returns 409.

**Impact:** Inventory cannot be reset by metadata changes or overwritten by concurrent service decrements.

**Trade-offs:** Hot stations serialize writes. Existing manual /reduceSlot consumes capacity without a Booking and has no general release/reset policy. Availability reads are snapshots, never reservations.

**Verification:** StationServiceImplTest.reductionUsesLockedRow/exhaustedStationRejectsReduction/capacityUpdatePreservesOccupiedSlots/capacityCannotBeReducedBelowOccupiedSlots/deletionRefusesBookingHistory/deletionWithoutBookingsSucceeds; ChargingStationControllerTest.updateCannotResetAvailableInventory; BookingIntegrationTest.stationResizePreservesOccupiedCapacityAndRejectsTooSmallTotal.

### Change ID: CHG-008 — Station search

**Module:** Station discovery.

**Files:** src/main/java/com/evcharging/repository/ChargingStationRepository.java; src/main/java/com/evcharging/service/StationService.java; src/main/java/com/evcharging/service/impl/StationServiceImpl.java; src/main/java/com/evcharging/controller/ChargingStationController.java.

**Class/Method:** searchStations; findByStationNameContainingIgnoreCaseOrLocationContainingIgnoreCase.

**Problem / Before:** No search operation existed; full listing was the only discovery option.

**After:**
~~~java
String normalized = query.strip();
return stationRepository.findByStationNameContainingIgnoreCaseOrLocationContainingIgnoreCase(
        normalized, normalized);
~~~

**What changed:** GET /station/search?query=... matches name or location, case-insensitively; stripped query length 1–100; DTO results.

**Why it changed:** A derived repository query is sufficient; no external search/geospatial technology is required.

**How it works internally:** Spring Data parses the method into bound containment predicates joined by OR; standard Containing handling escapes input wildcards.

**Impact:** Useful local search with a small understandable implementation.

**Trade-offs:** No radius, coordinates, ranking or connector filtering. Query length is bounded, result count is not; large datasets need pagination and query-plan review.

**Verification:** StationServiceImplTest.searchNormalizesQueryAndChecksBothNameAndLocation/blankSearchIsRejected/oversizedSearchIsRejected; ChargingStationControllerTest.searchReturnsMatchingDtos.

### Change ID: CHG-009 — Atomic booking creation and capacity locking

**Module:** Booking management.

**Files:** src/main/java/com/evcharging/service/impl/BookingServiceImpl.java; src/main/java/com/evcharging/repository/UserRepository.java; src/main/java/com/evcharging/repository/ChargingStationRepository.java; src/main/java/com/evcharging/service/BookingService.java.

**Class/Method:** createBooking; findByIdForUpdate queries.

**Problem:** Checking availability, saving and decrementing separately allowed races and partial writes.

**Before — separated operations:**
~~~java
boolean checkSlot = stationService.checkSlotAvailability(stationId);
~~~
~~~java
Booking savedBooking = bookingRepository.save(booking);

stationService.reduceAvailableSlot(stationId);
~~~

**After — transaction and lock ordering:**
~~~java
@Transactional
public BookingResponseDTO createBooking(Long userId, Long stationId, String requestKey) {
~~~
~~~java
User user = userRepository.findByIdForUpdate(userId)
        .orElseThrow(() -> new UserNotFoundException("User not found with ID: " + userId));
ChargingStation station = stationRepository.findByIdForUpdate(stationId)
        .orElseThrow(() -> new StationNotFoundException("Station not found with ID: " + stationId));
~~~
~~~java
station.setAvaialableSlots(station.getAvaialableSlots() - 1);
~~~
~~~java
Booking saved = bookingRepository.saveAndFlush(booking);
events.publishEvent(new BookingConfirmedEvent(saved.getId()));
return toDto(saved);
~~~

**What changed:** Positive IDs; user-then-station locks; capacity checked while locked; retained server-controlled 500 mock price; Confirmed insert/counter decrement in one transaction; flush/event/DTO return. Direct repository collaborators replace orchestration across separately scoped service writes.

**Why it changed:** A last-slot reservation is one business transaction; its successful read must remain protected through commit.

**How it works internally:** Spring's service proxy opens the transaction. Pessimistic row locks remain held until completion. Hibernate tracks the managed station and flushes its counter with the inserted booking. Runtime failures roll back both. User locking coordinates deletion; consistent ordering avoids opposite-order create locks.

**Impact:** After one caller takes the last slot, a waiter reads zero and receives conflict. No connector/time reservation semantics are implied.

**Trade-offs:** Same-station and same-user creates serialize. Mock payment runs under locks only because it is immediate/local. A slow real provider needs a different reservation/payment workflow; a database rollback cannot undo external money movement.

**Verification:** BookingIntegrationTest.concurrentLastSlotRequestsHaveExactlyOneWinner; concurrentRequestsRespectMultipleSlotCapacity; paymentDeclineLeavesNoBookingOrCapacityChange; outerRollbackUndoesSavedBookingAndCounterAndSuppressesNotification. Additional held-payment contention evidence is described in Section 9.

### Change ID: CHG-010 — Idempotent cancellation and dirty checking

**Module:** Booking lifecycle.

**Files:** src/main/java/com/evcharging/service/impl/BookingServiceImpl.java; src/main/java/com/evcharging/repository/BookingRepository.java; src/main/java/com/evcharging/entity/Booking.java.

**Class/Method:** cancelBooking; findStationIdById; Booking.CONFIRMED/CANCELLED.

**Problem:** Repeated cancellation always released capacity; station and booking lacked a shared transaction.

**Before:**
~~~java
booking.setBookingStatus("Cancelled");

ChargingStation station = booking.getStation();

station.setAvaialableSlots(station.getAvaialableSlots() + 1);
bookingRepository.save(booking);
~~~

**After:**
~~~java
Long stationId = bookingRepository.findStationIdById(bookingId)
        .orElseThrow(() -> new BookingNotFoundException("Booking not found with ID: " + bookingId));
ChargingStation station = stationRepository.findByIdForUpdate(stationId)
        .orElseThrow(() -> new StationNotFoundException("Station not found with ID: " + stationId));
Booking booking = requireBooking(bookingId);
if (Booking.CANCELLED.equals(booking.getBookingStatus())) {
    return;
}
~~~
~~~java
booking.setBookingStatus(Booking.CANCELLED);
station.setAvaialableSlots(station.getAvaialableSlots() + 1);
~~~

**What changed:** Transaction, positive ID, station lock, Cancelled no-op, supported-state check and guard against releasing above total capacity. Preserve status strings as constants.

**Why it changed:** Retrying an outcome must not repeat its inventory side effect. Loading Booking before waiting could cache stale status.

**How it works internally:** A scalar station-ID query avoids loading Booking; station lock comes first, then a fresh entity status read. All status writers follow this lock. Hibernate dirty checking persists both managed entities at commit without redundant save calls.

**Impact:** Sequential/concurrent cancellations release once; inconsistent state returns 409.

**Trade-offs:** Cancellation is not refunding. Only Confirmed/Cancelled are defined; no arbitrary status-update endpoint was invented.

**Verification:** BookingIntegrationTest.lifecycleReadsDtosFiltersAndCancelsOnlyOnce; concurrentCancellationReleasesExactlyOneSlot.

### Change ID: CHG-011 — Persistent booking request replay keys

**Module:** Booking idempotency.

**Files:** src/main/java/com/evcharging/entity/Booking.java; src/main/java/com/evcharging/repository/BookingRepository.java; src/main/java/com/evcharging/controller/BookingController.java; src/main/java/com/evcharging/service/BookingService.java; src/main/java/com/evcharging/service/impl/BookingServiceImpl.java.

**Class/Method:** createBooking; Booking.requestKey; findByRequestKey.

**Problem / Before:** No key/header/lookup existed; a lost response could cause duplicate creation.

**After:**
~~~java
@Column(name = "request_key", length = 64, unique = true)
private String requestKey;
~~~
~~~java
if (requestKey != null) {
    var previous = bookingRepository.findByRequestKey(requestKey);
    if (previous.isPresent()) {
        Booking booking = previous.get();
        if (!booking.getUser().getId().equals(userId) || booking.getStation().getId() != stationId.longValue()) {
            throw new DomainConflictException("Idempotency-Key has already been used for a different booking request");
        }
        return toDto(booking);
    }
}
~~~

**What changed:** Optional Idempotency-Key header, 1–64 ASCII letters/digits/dot/underscore/colon/hyphen, nullable unique column and lookup after user/station locks but before capacity/payment. Same user/station replays current stored DTO; other resources conflict. Cancelled replay remains Cancelled.

**Why it changed:** Durable request identity makes supported retries safe after ambiguous HTTP delivery.

**How it works internally:** Same-resource retries share locks, so the waiter sees the committed key and skips mock payment/counter/event. Database uniqueness protects key races involving different resources.

**Impact:** Same-resource replay creates one booking and calls mock payment once; absent key retains per-request creation.

**Trade-offs:** This is not real-payment exactly-once processing. Different-resource requests with one key can call the mock twice before one unique insert rolls back; loser may receive DATA_CONFLICT. A real payment cannot be undone by that rollback. No provider idempotency, failure ledger, key expiry or response snapshot exists. Key identity compares user/station only; ignored request amount does not affect it. Replay still returns 201/Location and requires existing lockable resources.

**Verification:** BookingIntegrationTest.concurrentRetryUsesSameBookingAndMockPaymentOnce; reusedKeyWithDifferentUserIsConflictAndCancelledKeyDoesNotCreateAgain; BookingControllerTest.createsAndReturnsDtoLocationAndKey/invalidKeyDomainValidationHas400.

### Change ID: CHG-012 — Booking API responses, filters and fetch plans

**Module:** Booking HTTP/JPA reads.

**Files:** src/main/java/com/evcharging/controller/BookingController.java; src/main/java/com/evcharging/dto/BookingRequestDTO.java; src/main/java/com/evcharging/service/BookingService.java; src/main/java/com/evcharging/service/impl/BookingServiceImpl.java; src/main/java/com/evcharging/repository/BookingRepository.java; src/main/java/com/evcharging/entity/Booking.java.

**Class/Method:** createBooking; getBookingsByUser/getBookingsByStation; getAllBookings/toDto; relationship annotations.

**Problem:** Create returned no resource ID; scoped lists absent; only null-ID validation; implicit fetch/session behavior and repeated mapping.

**Before:**
~~~java
public void createBooking( @Valid @RequestBody BookingRequestDTO request)
{
    bookingService.createBooking(request.getUserId(), request.getStationId());
~~~
~~~java
@NotNull
private Long userId;
~~~

**After:**
~~~java
BookingResponseDTO booking = bookingService.createBooking(request.getUserId(), request.getStationId(), requestKey);
return ResponseEntity.created(URI.create("/booking/" + booking.getBookingId())).body(booking);
~~~
~~~java
@NotNull
@Positive
private Long userId;
~~~
~~~java
@Override
@EntityGraph(attributePaths = {"user", "station"})
List<Booking> findAll();
~~~

**What changed:** 201 DTO/Location create response; user/station filter endpoints with parent existence checks; positive request/service IDs; centralized mapping inside read-only service transactions; lazy user/station relationships and list/filter fetch graphs; FK indexes; amount/status checks and nullability. Remove unused request imports/entity JSON back-reference.

**Why it changed:** Identify created resources and fetch/map required data before closing the transaction, rather than relying on HTTP session lifetime.

**How it works internally:** Entity graphs fetch required relations for lists; lazy relations remain the general policy. DTO scalars are copied within the transaction. Database indexes support scoped queries.

**Impact:** Stable existing booking DTO fields, clear input/resource errors and useful scoped reads.

**Trade-offs:** Empty 200 create becomes JSON 201 intentionally. Lists remain unpaginated. Positive request amount remains required but ignored exactly as the original pricing behavior did: server returns 500. double remains a demo type, not a currency-safe money design.

**Verification:** BookingControllerTest.createsAndReturnsDtoLocationAndKey/rejectsNullAndNonpositiveIdsAndAmountBeforeCallingService/filtersCallCorrectServices/cancellationRequiresIdAndPreservesExisting200; BookingIntegrationTest.lifecycleReadsDtosFiltersAndCancelsOnlyOnce/realMvcRejectsMassAssignmentAndMapsResponsesWithoutOpenSession.

### Change ID: CHG-013 — Honest mock providers and payment failure handling

**Module:** Payment/provider configuration.

**Files:** src/main/java/com/evcharging/config/AppConfig.java; src/main/java/com/evcharging/payment/RazorpayGateway.java; src/main/java/com/evcharging/payment/StripeGateway.java; src/main/java/com/evcharging/service/impl/PaymentServiceImpl.java; src/main/java/com/evcharging/exception/PaymentFailedException.java.

**Class/Method:** paymentGateway/notificationSender/requireMockMode; gateways.pay; processPayment.

**Problem:** Success text implied money movement; unknown provider names silently fell back; unsafe provider exception details could escape.

**Before — StripeGateway:**
~~~java
public boolean pay(double amount) {
    System.out.println("Payment is done using stripe");
    return true;
}
~~~
**Before — AppConfig fallback:**
~~~java
return new StripeGateway();
~~~

**After — explicit mock:**
~~~java
/** Development mock only. No Stripe API request or money movement occurs. */
public class StripeGateway implements PaymentGateway {
    @Override
    public boolean pay(double amount) {
        return true;
    }
}
~~~
~~~java
if (!Double.isFinite(amount) || amount <= 0) {
    throw new BadRequestException("Payment amount must be finite and greater than zero");
}
~~~
**After — actual configuration selection and normalization:**
~~~java
requireMockMode(paymentMode, "payment.mode");
return switch (paymentGateway) {
    case "razorpay" -> new RazorpayGateway();
    case "stripe" -> new StripeGateway();
    default -> throw new IllegalArgumentException("payment.gateway must be stripe or razorpay");
};
~~~
~~~java
return value.trim().toLowerCase(Locale.ROOT);
~~~

**What changed:** Label both gateways as mocks, normalize constructor-injected selections, reject unknown/non-mock modes at startup, validate finite positive payment amounts, preserve declines and convert provider RuntimeException into sanitized PaymentFailedException. Remove amount/success console output.

**Why it changed:** An interface seam is useful, but demonstrations must not masquerade as integrations. Typos should fail visibly.

**How it works internally:** Spring creates one validated strategy bean. The service depends on its interface. Booking converts false to a runtime payment error and rolls back; advice maps failures to safe 502.

**Impact:** Deterministic credential-free local simulation and tested failure paths.

**Trade-offs:** No provider SDK, payment records, verification/webhooks/status/refunds or real financial idempotency. Production mode does not magically turn the mocks into live providers.

**Verification:** AppConfigTest.acceptsNormalizedKnownMockSelections/selectsOtherSupportedMocks/rejectsUnknownPaymentGateway/rejectsUnknownNotificationType/rejectsUnimplementedRealPaymentMode/rejectsUnimplementedRealNotificationMode; PaymentServiceImplTest.rejectsInvalidAmountsWithoutCallingProvider/forwardsValidAmountAndSuccess/preservesProviderDeclineForBookingServiceToHandle/sanitizesProviderFailure/bothNamedAdaptersAreDeterministicDevelopmentMocks.

### Change ID: CHG-014 — After-commit best-effort mock notifications

**Module:** Notifications/transaction events.

**Files:** src/main/java/com/evcharging/notification/BookingConfirmedEvent.java; src/main/java/com/evcharging/notification/BookingNotificationListener.java; src/main/java/com/evcharging/notification/EmailNotificationSender.java; src/main/java/com/evcharging/notification/SmsNotificationSender.java; src/main/java/com/evcharging/service/impl/NotificationServiceImpl.java; src/main/java/com/evcharging/service/impl/BookingServiceImpl.java.

**Class/Method:** onBookingConfirmed; sender.send; sendBookingConfirmation; createBooking.

**Problem:** Direct notification in the write sequence could precede rollback or fail a partially written booking; senders said messages were sent.

**Before:**
~~~java
notificationService.sendBookingConfirmation("Booking confirmed successfully");
~~~
~~~java
System.out.println("Email sent : " + message);
~~~

**After — creation event:**
~~~java
events.publishEvent(new BookingConfirmedEvent(saved.getId()));
~~~
**After — actual listener statements, omitting its explanatory comment:**
~~~java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onBookingConfirmed(BookingConfirmedEvent event) {
    try {
        notificationService.sendBookingConfirmation("Booking confirmed successfully");
    } catch (RuntimeException exception) {
        log.warn("Mock booking notification failed after commit");
    }
}
~~~

**What changed:** In-memory event after flush, AFTER_COMMIT listener, best-effort runtime-failure isolation, explicit mock email/SMS logs without message content, simpler delegating service and no redundant stdout.

**Why it changed:** Only committed bookings should trigger confirmation; a sender failure must not misrepresent the persisted booking result.

**How it works internally:** The transactional listener registers with the current transaction, skips rollback and runs after commit. It is synchronous, not @Async. The catch prevents sender runtime exceptions escaping.

**Impact:** Correct transaction ordering and isolated notification failure.

**Trade-offs:** Event is not durable; crash after commit can lose it. No outbox/retry/delivery tracking/recipient/provider exists. Real delivery needs a separate design.

**Verification:** BookingNotificationListenerTest.listenerDelegatesCommittedConfirmation/listenerDoesNotFailBookingResponseOrLogProviderDetails/notificationServiceDelegatesToSelectedSender/mockSendersClearlyLabelTheirBehaviorAndDoNotLogContents; BookingIntegrationTest.notificationRunsAfterCommitVisibleToAnIndependentConnection/outerRollbackUndoesSavedBookingAndCounterAndSuppressesNotification/notificationFailureDoesNotUndoBooking.

### Change ID: CHG-015 — Consistent sanitized errors

**Module:** Exception handling.

**Files:** src/main/java/com/evcharging/global/exception/handler/ApiError.java; src/main/java/com/evcharging/global/exception/handler/GlobalExceptionHandler.java; src/main/java/com/evcharging/exception/BadRequestException.java; src/main/java/com/evcharging/exception/DomainConflictException.java; src/main/java/com/evcharging/exception/StationNotFoundException.java; src/main/java/com/evcharging/exception/PaymentFailedException.java.

**Class/Method:** ApiError; handleNotFound/handleConflict/handleBadRequest/handlePaymentFailure/handleDataConflict/handleLockConflict/handleConstraintViolation/handleExceptionInternal/handleUnexpected.

**Problem:** Incompatible String/map formats and generic server errors for normal domain outcomes.

**Before:**
~~~java
return new ResponseEntity<>(
        ex.getMessage(), HttpStatus.NOT_FOUND
        );
~~~

**After:**
~~~java
public record ApiError(Instant timestamp, int status, String code, String message,
                       String path, Map<String, String> fieldErrors) {
    public ApiError {
        fieldErrors = Map.copyOf(fieldErrors);
    }
}
~~~
~~~java
@ExceptionHandler(DomainConflictException.class)
public ResponseEntity<Object> handleConflict(DomainConflictException exception, WebRequest request) {
    return error(HttpStatus.CONFLICT, "DOMAIN_CONFLICT", exception.getMessage(), request, Map.of());
}
~~~

**What changed:** UTC timestamp/status/code/message/path/fieldErrors contract; precise domain types; sanitized provider/database/unexpected failures; MVC JSON/validation/parameter/type errors; retry-oriented lock conflict 409. ResponseEntityExceptionHandler preserves framework statuses/headers including Allow for 405. Unhandled log records type, not untrusted details.

**Why it changed:** Clients need actionable error categories without SQL or stack traces.

**How it works internally:** @RestControllerAdvice handles domain exceptions; superclass routing handles framework MVC errors through the overridden method. ApiError copies its map immutably.

**Impact:** Predictable safe responses across old/new APIs.

**Trade-offs:** Error bodies intentionally change; database conflicts may remain generic. Secure operational diagnostics may need more structured observability later.

**Verification:** GlobalExceptionHandlerTest.missingResourceHasConsistent404Body/domainConflictUses409/badRequestUses400/paymentFailureUsesSanitized502/databaseConflictDoesNotExposeSql/lockContentionProducesRetryableConflict/unexpectedFailureDoesNotExposeInternalDetails/malformedJsonUses400/fieldValidationIncludesFieldErrors/missingParameterUses400/wrongParameterTypeUses400/mvcMethodNotAllowedKeepsStatusAndAllowHeader.

### Change ID: CHG-016 — Behavior, transaction and concurrency tests

**Module:** Automated verification.

**Files:** src/test/java/com/evcharging/service/impl/UserServiceImplTest.java; src/test/java/com/evcharging/service/impl/StationServiceImplTest.java; src/test/java/com/evcharging/service/impl/PaymentServiceImplTest.java; src/test/java/com/evcharging/controller/UserControllerTest.java; src/test/java/com/evcharging/controller/ChargingStationControllerTest.java; src/test/java/com/evcharging/controller/BookingControllerTest.java; src/test/java/com/evcharging/global/exception/handler/GlobalExceptionHandlerTest.java; src/test/java/com/evcharging/config/AppConfigTest.java; src/test/java/com/evcharging/notification/BookingNotificationListenerTest.java; src/test/java/com/evcharging/BookingIntegrationTest.java; src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker; src/test/resources/mockito-extensions/org.mockito.plugins.MemberAccessor.

**Class/Method:** UserServiceImplTest; StationServiceImplTest; PaymentServiceImplTest; UserControllerTest; ChargingStationControllerTest; BookingControllerTest; GlobalExceptionHandlerTest; AppConfigTest; BookingNotificationListenerTest; BookingIntegrationTest.

**Problem / Before:** No corresponding business/MVC/integration tests existed; only the context test described in CHG-002.

**After — actual last-slot assertions:**
~~~java
assertThat(results).containsExactlyInAnyOrder("created", "full");
assertThat(bookingRepository.count()).isEqualTo(1);
assertThat(stationRepository.findById(station.getId()).orElseThrow().getAvaialableSlots()).isZero();
verify(paymentService, times(1)).processPayment(500);
verify(sender, times(1)).send(anyString());
~~~
**After — test portability configuration:**
~~~text
mock-maker-subclass
~~~
The separate org.mockito.plugins.MemberAccessor file contains:
~~~text
member-accessor-reflection
~~~

**What changed:** Meaningful service/MVC/provider/error tests; real application/JPA/constraint/rollback/concurrency tests; start barriers/bounded workers; independent-connection after-commit check; a held-first-payment contention window and actual embedded-server HTTP coverage. Mockito uses its subclass maker because all mocked collaborators are interfaces and dynamic instrumentation attachment stalled in this environment.

**Why it changed:** Mocked save verification cannot establish transaction/lock behavior. Tests must exercise those mechanisms through real service proxies and a database. Interface mocks need no inline instrumentation.

**How it works internally:** Mockito isolates unit collaborators. MVC tests exercise mapping/validation/advice. The integration class has no outer test transaction; workers invoke actual transactional proxies independently. H2 creates/drops isolated data. Server tests bind a random port.

**Impact:** Regression evidence targets actual failure modes without production credentials.

**Trade-offs:** Finite schedules do not cover every interleaving. H2 is not PostgreSQL. Subclass mocking does not support arbitrary final/static mocking; none is needed here. Actual results belong in Section 9.

**Verification:** Checked-in wrapper clean test, exact observed execution in Section 9; no inference of pass status from test existence.

### Change ID: CHG-017 — Audit and educational documentation

**Module:** Documentation.

**Files:** docs/INITIAL_CODE_AUDIT.md; docs/EV_CHARGING_BACKEND_CHANGE_REPORT.md.

**Class/Method:** Not applicable.

**Problem / Before:** Neither file existed.

**After — actual report headings:**
~~~text
## 3. Problems Discovered
## 4. Complete Change Log
## 7. Database Changes
## 9. Testing Report
## 13. Learning Walkthrough
~~~

**What changed:** Initial audit before substantive source edits; comprehensive source-evidenced report with architecture, changes, APIs, migration, verification, run/learning flows and roadmap.

**Why it changed:** A learner needs the connection between changed statements, framework mechanisms and practical limitations.

**How it works internally:** Baseline snippets come from the recorded Git commit, changed/untracked paths from Git inventory and test outcomes from execution evidence. Secrets are never quoted.

**Impact:** Reviewable, reproducible engineering explanation.

**Trade-offs:** Future code changes must maintain documentation alongside source.

**Verification:** Git diff/status compared with Section 5; final evidence in Section 9.

### Change ID: CHG-018 — Remove unused multipart parsing surface

**Module:** HTTP configuration/security.

**Files:** src/main/resources/application.properties; src/test/java/com/evcharging/BookingIntegrationTest.java.

**Class/Method:** Boot multipart auto-configuration; unusedMultipartParsingIsDisabledAndUploadsAreRejectedOverHttp.

**Problem:** The retained Boot 3.5.3 dependency management selects Tomcat 10.1.42. The official Tomcat advisory lists CVE-2025-61795 multipart temporary-file denial of service for versions through 10.1.46, fixed in 10.1.47. None of these JSON controllers requires uploads. [Official Tomcat security advisory](https://tomcat.apache.org/security-10.html).

**Before:** No multipart override existed in application.properties; the default multipart auto-configuration was enabled. There is no invented original source line.

**After:**
~~~properties
spring.servlet.multipart.enabled=false
~~~
**After — actual integration assertion:**
~~~java
assertThat(context.getBeansOfType(MultipartResolver.class)).isEmpty();
~~~
~~~java
assertThat(response.getStatusCode().value()).isEqualTo(415);
assertThat(response.getBody()).contains("HTTP_415");
~~~

**What changed:** Disable unused multipart resolver creation. Add a real embedded-server HTTP multipart request check plus bean inspection.

**Why it changed:** Removing an unused parser is a small justified surface reduction while preserving the requested Java/Boot versions.

**How it works internally:** Boot's multipart configuration condition reads this property; no MultipartResolver is registered. JSON-only MVC handlers reject multipart input as unsupported media type rather than binding a JSON DTO.

**Impact:** The API's actual input surface matches its JSON contracts and the unused parsing path is disabled.

**Trade-offs:** This is not a dependency upgrade, exhaustive SCA or exploit test. The retained library versions remain in affected advisory ranges. A tested compatible Boot 3.5.x BOM patch is required before broader deployment. Future upload endpoints must deliberately revisit parsing, limits and dependency versions.

**Verification:** BookingIntegrationTest.unusedMultipartParsingIsDisabledAndUploadsAreRejectedOverHttp; actual execution in Section 9. No blanket “secure” claim follows from this mitigation.

## 5. Files Created, Modified, and Deleted

This inventory was constructed from git status --porcelain=v1 --untracked-files=all and the tracked baseline diff. It includes new files before staging. Build outputs and .git internals are excluded. The old/new context-test paths are a relocation of the same testing purpose, not deletion of a requirement. Unchanged files such as DemoApplication, BookingResponseDTO, provider/service interfaces, wrapper scripts and pom.properties were reviewed and intentionally retained.

| File | Action | Reason | Related Change ID |
|---|---|---|---|
| .gitignore | Modified | Allow placeholder-only env template | CHG-001 |
| pom.xml | Modified | Remove duplicate driver; Java17 target; test-only H2 | CHG-002 |
| src/main/java/com/evcharging/config/AppConfig.java | Modified | Validate explicit mock strategy selections | CHG-013 |
| src/main/java/com/evcharging/controller/BookingController.java | Modified | 201 DTO/Location, request key and filters | CHG-011, CHG-012 |
| src/main/java/com/evcharging/controller/ChargingStationController.java | Modified | DTOs, valid domain errors, update/delete/search | CHG-006, CHG-007, CHG-008 |
| src/main/java/com/evcharging/controller/UserController.java | Modified | DTO contracts and safe update/delete | CHG-003, CHG-005 |
| src/main/java/com/evcharging/dto/BookingRequestDTO.java | Modified | Positive IDs and import cleanup | CHG-012 |
| src/main/java/com/evcharging/entity/Booking.java | Modified | Lazy relations, checks, indexes and request key | CHG-010, CHG-011, CHG-012 |
| src/main/java/com/evcharging/entity/ChargingStation.java | Modified | Nonnull bounded details and inventory constraint | CHG-006 |
| src/main/java/com/evcharging/entity/User.java | Modified | Initialized history, constrained normalized identity, remove entity JSON annotations | CHG-003, CHG-004 |
| src/main/java/com/evcharging/global/exception/handler/GlobalExceptionHandler.java | Modified | Consistent sanitized domain/MVC/database errors | CHG-015 |
| src/main/java/com/evcharging/notification/EmailNotificationSender.java | Modified | Mock sender logs without message contents | CHG-014 |
| src/main/java/com/evcharging/notification/SmsNotificationSender.java | Modified | Mock sender logs without message contents | CHG-014 |
| src/main/java/com/evcharging/payment/RazorpayGateway.java | Modified | Clearly label deterministic development payment mock | CHG-013 |
| src/main/java/com/evcharging/payment/StripeGateway.java | Modified | Clearly label deterministic development payment mock | CHG-013 |
| src/main/java/com/evcharging/repository/BookingRepository.java | Modified | Fetch graphs, filters, history checks and replay/scalar lookups | CHG-005, CHG-007, CHG-010, CHG-011, CHG-012 |
| src/main/java/com/evcharging/repository/ChargingStationRepository.java | Modified | Pessimistic station query and search | CHG-007, CHG-008, CHG-009 |
| src/main/java/com/evcharging/repository/UserRepository.java | Modified | Duplicate queries and user row lock | CHG-004, CHG-005, CHG-009 |
| src/main/java/com/evcharging/service/BookingService.java | Modified | DTO creation/replay signature and scoped lists | CHG-009, CHG-011, CHG-012 |
| src/main/java/com/evcharging/service/StationService.java | Modified | Station update/delete/search contracts | CHG-007, CHG-008 |
| src/main/java/com/evcharging/service/UserService.java | Modified | User update/delete contracts | CHG-005 |
| src/main/java/com/evcharging/service/impl/BookingServiceImpl.java | Modified | Atomic locked lifecycle, replay, mapping, postcommit event | CHG-009, CHG-010, CHG-011, CHG-012, CHG-014 |
| src/main/java/com/evcharging/service/impl/NotificationServiceImpl.java | Modified | Keep delegation; remove misleading stdout | CHG-014 |
| src/main/java/com/evcharging/service/impl/PaymentServiceImpl.java | Modified | Finite amount validation and safe failures | CHG-013 |
| src/main/java/com/evcharging/service/impl/StationServiceImpl.java | Modified | Validated inventory, lock all writes, resize/history/search | CHG-006, CHG-007, CHG-008 |
| src/main/java/com/evcharging/service/impl/UserServiceImpl.java | Modified | Normalize/unique registration and safe locked lifecycle | CHG-004, CHG-005 |
| src/main/resources/application.properties | Modified | External secrets, safe local/schema/JSON/multipart defaults | CHG-001, CHG-018 |
| src/test/java/com/example/demo/DemoApplicationTests.java | Deleted | Relocate context test into application package; retain its purpose | CHG-002 |
| .env.example | Created | Safe environment variable template | CHG-001 |
| docs/EV_CHARGING_BACKEND_CHANGE_REPORT.md | Created | Comprehensive evidence and learning report | CHG-017 |
| docs/INITIAL_CODE_AUDIT.md | Created | Baseline audit before source changes | CHG-017 |
| src/main/java/com/evcharging/dto/StationRequestDTO.java | Created | Validated station creation input | CHG-006 |
| src/main/java/com/evcharging/dto/StationResponseDTO.java | Created | Explicit stable station response | CHG-006 |
| src/main/java/com/evcharging/dto/StationUpdateRequestDTO.java | Created | Metadata/capacity input without availability reset | CHG-007 |
| src/main/java/com/evcharging/dto/UserRequestDTO.java | Created | Normalized validated user input | CHG-003, CHG-004 |
| src/main/java/com/evcharging/dto/UserResponseDTO.java | Created | Bounded user response without graph | CHG-003 |
| src/main/java/com/evcharging/exception/BadRequestException.java | Created | Typed domain failure mapped to safe error | CHG-015 |
| src/main/java/com/evcharging/exception/DomainConflictException.java | Created | Typed domain failure mapped to safe error | CHG-015 |
| src/main/java/com/evcharging/exception/PaymentFailedException.java | Created | Typed domain failure mapped to safe error | CHG-015 |
| src/main/java/com/evcharging/exception/StationNotFoundException.java | Created | Typed domain failure mapped to safe error | CHG-015 |
| src/main/java/com/evcharging/global/exception/handler/ApiError.java | Created | Immutable error contract | CHG-015 |
| src/main/java/com/evcharging/notification/BookingConfirmedEvent.java | Created | Local committed-booking signal | CHG-014 |
| src/main/java/com/evcharging/notification/BookingNotificationListener.java | Created | After-commit best-effort notification | CHG-014 |
| src/main/resources/application-dev.properties | Created | Isolated development schema opt-in | CHG-001 |
| src/main/resources/application-prod.properties | Created | Existing-schema validation defaults | CHG-001 |
| src/test/java/com/evcharging/BookingIntegrationTest.java | Created | Behavior/MVC/provider/transaction/concurrency regression coverage | CHG-016 |
| src/test/java/com/evcharging/DemoApplicationTests.java | Created | Correct Boot discovery and activate isolated test profile | CHG-002 |
| src/test/java/com/evcharging/config/AppConfigTest.java | Created | Behavior/MVC/provider/transaction/concurrency regression coverage | CHG-016 |
| src/test/java/com/evcharging/controller/BookingControllerTest.java | Created | Behavior/MVC/provider/transaction/concurrency regression coverage | CHG-016 |
| src/test/java/com/evcharging/controller/ChargingStationControllerTest.java | Created | Behavior/MVC/provider/transaction/concurrency regression coverage | CHG-016 |
| src/test/java/com/evcharging/controller/UserControllerTest.java | Created | Behavior/MVC/provider/transaction/concurrency regression coverage | CHG-016 |
| src/test/java/com/evcharging/global/exception/handler/GlobalExceptionHandlerTest.java | Created | Behavior/MVC/provider/transaction/concurrency regression coverage | CHG-016 |
| src/test/java/com/evcharging/notification/BookingNotificationListenerTest.java | Created | Behavior/MVC/provider/transaction/concurrency regression coverage | CHG-016 |
| src/test/java/com/evcharging/service/impl/PaymentServiceImplTest.java | Created | Behavior/MVC/provider/transaction/concurrency regression coverage | CHG-016 |
| src/test/java/com/evcharging/service/impl/StationServiceImplTest.java | Created | Behavior/MVC/provider/transaction/concurrency regression coverage | CHG-016 |
| src/test/java/com/evcharging/service/impl/UserServiceImplTest.java | Created | Behavior/MVC/provider/transaction/concurrency regression coverage | CHG-016 |
| src/test/resources/application-test.properties | Created | Isolated H2 datasource and schema | CHG-002 |
| src/test/resources/mockito-extensions/org.mockito.plugins.MemberAccessor | Created | Use interface/subclass mocks and reflection without JVM instrumentation attachment | CHG-016 |
| src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker | Created | Use interface/subclass mocks and reflection without JVM instrumentation attachment | CHG-016 |

## 6. API Documentation

Base URL defaults to http://127.0.0.1:8081. All 19 application endpoints below require **no authentication** in the current local model. Loopback binding is configurable; no ownership/role checks exist. Do not publish this API to untrusted clients before defining and implementing that model. There is no permissive CORS configuration added.

All body endpoints accept JSON. Unknown fields are rejected with 400; malformed/missing required JSON is 400; unsupported media types are 415. Incorrect parameter types are 400. Framework 405 responses preserve Allow. Unexpected failures are sanitized 500. These common outcomes apply in addition to the endpoint-specific errors below.

### 6.1 Actual request and response shapes

These are illustrative examples, not a claim that IDs 1/2 exist in an external database. Arrays contain the same response objects and may be empty. No ordering guarantee or pagination was added.

**User request U-REQ**, used for registration/update:
~~~json
{"name":"Ada","email":"ada@example.com"}
~~~

name is nonblank and at most 100 characters; email is nonblank, valid under Jakarta @Email and at most 254 characters. Both are stripped; email is lowercase using Locale.ROOT before validation. IDs/bookings are not accepted fields.

**User response U-RESP:**
~~~json
{"id":1,"name":"Ada","email":"ada@example.com"}
~~~

**Station create request S-CREATE:**
~~~json
{"stationName":"Central EV","location":"Pune","totalSlots":2,"avaialableSlots":2}
~~~

stationName/location are stripped, nonblank and at most 150/255 characters. totalSlots is a required integer >= 1; avaialableSlots is a required integer >= 0 and <= totalSlots. The existing misspelling is deliberately preserved. id is not writable.

**Station update request S-UPDATE:**
~~~json
{"stationName":"Central EV","location":"Pune","totalSlots":3}
~~~

Same detail/positive-total rules. avaialableSlots is not accepted. The service preserves occupied inventory and rejects totalSlots below occupied capacity with 409.

**Station response S-RESP:**
~~~json
{"id":2,"stationName":"Central EV","location":"Pune","totalSlots":2,"avaialableSlots":2}
~~~

**Booking create request B-REQ:**
~~~json
{"userId":1,"stationId":2,"amount":500}
~~~

Both IDs are required positive integers; amount is required and positive. amount is a compatibility field and is ignored for pricing, as it was before this task. Sending amount:1 still produces server-controlled amount 500. No currency, duration, start/end time or connector field exists.

**Booking response B-RESP:**
~~~json
{"bookingId":1,"userId":1,"stationId":2,"bookingStatus":"Confirmed","amount":500.0}
~~~

Cancellation changes bookingStatus to Cancelled while retaining the other fields. requestKey is not exposed in this response. Optional Idempotency-Key uses 1–64 ASCII letters/digits or . _ : -; it is not stripped or case-normalized. Matching same-user/station retries replay the current persisted booking, including Cancelled. Reusing a key with different resources conflicts. Without a key, each call is a new attempt. Concurrent distinct-resource key races may invoke the mock twice before the losing unique insertion rolls back; this is not external payment exactly-once processing.

### 6.2 User endpoints

| Method and URL | Origin / purpose | Request body | Example response | Success | Endpoint errors | Auth / validation |
|---|---|---|---|---|---|---|
| POST /users/reg | Existing; register user | U-REQ above | Plain text: User Registered Successfully | 200 | 400 validation/unknown fields; 409 duplicate email | None; U-REQ rules |
| GET /users/{id} | Existing; retrieve user | None | U-RESP above | 200 | 400 invalid Long; 404 missing user | None; numeric ID; nonexistent nonpositive ID also resolves as missing |
| GET /users/all | Existing; list users | None | [{"id":1,"name":"Ada","email":"ada@example.com"}] | 200 | Common errors | None; unpaginated |
| PUT /users/{id} | New; replace user details, retain ID/history | U-REQ above | U-RESP above | 200 | 400 invalid input/type; 404 missing; 409 another user's email | None; U-REQ rules |
| DELETE /users/{id} | New; delete only without booking history | None | Empty body | 204 | 400 invalid Long; 404 missing; 409 any historical booking | None; no cascade, Cancelled history still blocks |

### 6.3 Station endpoints

| Method and URL | Origin / purpose | Request body | Example response | Success | Endpoint errors | Auth / validation |
|---|---|---|---|---|---|---|
| POST /station/reg | Existing; create station | S-CREATE | Plain text: Station added successfully | 200 | 400 validation/unknown fields; 409 DB conflict | None; S-CREATE bounds |
| GET /station/{id} | Existing; retrieve station | None | S-RESP | 200 | 400 invalid Long; 404 missing | None; numeric ID |
| GET /station/all | Existing; list stations | None | [{"id":2,"stationName":"Central EV","location":"Pune","totalSlots":2,"avaialableSlots":2}] | 200 | Common errors | None; unpaginated |
| GET /station/isAvailable/{id} | Existing; read capacity snapshot | None | Plain text represented with quotes: "Slot is available for this Station " (final space retained) | 200 | 400 invalid Long; 404 missing; 409 no capacity | None; this does not reserve a slot |
| PUT /station/reduceSlot/{id} | Existing; manual aggregate decrement | None | Empty body | 200 | 400 invalid Long; 404 missing; 409 no capacity/lock conflict | None; protected mutation; creates no Booking |
| PUT /station/{id} | New; update details/capacity | S-UPDATE | S-RESP with new details/counters | 200 | 400 validation/unknown fields; 404 missing; 409 total below occupied/lock conflict | None; no availability reset field |
| DELETE /station/{id} | New; delete unreferenced station | None | Empty body | 204 | 400 invalid Long; 404 missing; 409 booking history/lock conflict | None; Cancelled history blocks |
| GET /station/search?query=Pune | New; name/location contains search | None | Same response array as /station/all | 200 | 400 missing/blank/>100-character query | None; strip query, case-insensitive contains |

### 6.4 Booking endpoints

| Method and URL | Origin / purpose | Request body / header | Example response | Success | Endpoint errors | Auth / validation |
|---|---|---|---|---|---|---|
| POST /booking/createBooking | Existing, response extended; reserve capacity | B-REQ; optional Idempotency-Key: request-1 | B-RESP; Location: /booking/1 | 201, including key replay | 400 IDs/amount/key/JSON; 404 user/station; 409 full/key reuse/constraint/lock; 502 mock payment failure | None; positive IDs/amount, key format; fixed server price |
| GET /booking/{id} | Existing; retrieve booking | None | B-RESP | 200 | 400 invalid/nonpositive ID; 404 missing | None; positive ID |
| GET /booking/all | Existing; list bookings | None | [{"bookingId":1,"userId":1,"stationId":2,"bookingStatus":"Confirmed","amount":500.0}] | 200 | Common errors | None; unpaginated |
| GET /booking/user/{userId} | New; list a user's bookings | None | Same booking array; [] if existing user has none | 200 | 400 invalid/nonpositive ID; 404 missing user | None; includes Confirmed and Cancelled |
| GET /booking/station/{stationId} | New; list station bookings | None | Same booking array; [] if existing station has none | 200 | 400 invalid/nonpositive ID; 404 missing station | None; includes Confirmed and Cancelled |
| PUT /booking/cancel?id=1 | Existing; cancel once/replay safely | No body; required id query | Empty body | 200, also when already Cancelled | 400 missing/invalid/nonpositive ID; 404 missing; 409 unsupported status/inconsistent capacity/lock | None; no refund implied |

No payment initiation/status/verification endpoint, notification endpoint, login or arbitrary booking-status endpoint exists. Do not infer one from the provider/service class names.

The retained Actuator starter also exposes framework management routes separately from the 19 custom routes. Startup reported one endpoint beneath /actuator: the default health endpoint. GET /actuator/health has no request body/authentication and returns framework health JSON (typically {"status":"UP"}, 200 when healthy; 503 can represent an unhealthy datasource). GET /actuator is the default discovery document with framework-generated links. These are Boot defaults, not newly implemented application APIs; no custom health details or broad management exposure was configured. Their bodies are not promised to use ApiError. Reassess management authentication/exposure together with any deployment security model.

### 6.5 Error response contract and compatibility

Illustrative validation error:
~~~json
{
  "timestamp":"2026-10-17T12:00:00Z",
  "status":400,
  "code":"VALIDATION_FAILED",
  "message":"Request validation failed",
  "path":"/users/reg",
  "fieldErrors":{"email":"must be a well-formed email address"}
}
~~~

Field message wording comes from the validation provider and can vary; clients should use status/code/field names. Domain/payment/database/lock/unexpected errors return fieldErrors:{}. Missing/invalid parameter errors can include the parameter name in fieldErrors even though their code is not VALIDATION_FAILED.

| Status | Code examples | Meaning |
|---|---|---|
| 400 | VALIDATION_FAILED, INVALID_REQUEST, MALFORMED_JSON, MISSING_PARAMETER, INVALID_PARAMETER | Correct the request. Unknown fields use MALFORMED_JSON. |
| 404 | RESOURCE_NOT_FOUND | Required resource absent. |
| 409 | DOMAIN_CONFLICT, DATA_CONFLICT, CONCURRENT_MODIFICATION | Capacity/history/key/uniqueness conflict or retryable contention. |
| 415 / 405 | HTTP_415 / HTTP_405 | Unsupported body type / method. |
| 502 | PAYMENT_FAILED | Mock payment declined or failed; no real provider claim. |
| 500 | INTERNAL_ERROR | Sanitized unexpected failure, no stack trace/SQL. |

Intentional compatibility changes: booking creation is now JSON 201 with Location rather than empty 200; user reads no longer contain the nested bookings graph; request DTOs reject entity IDs/relationships/unknown fields; error String/maps become the error object; invalid states now fail instead of being silently persisted. Existing paths, registration success text/status, booking DTO fields, Confirmed/Cancelled values, cancellation 200, fixed 500 demonstration price and avaialableSlots spelling remain.

## 7. Database Changes

### 7.1 Mappings, queries and transactions

The application database remains PostgreSQL. No live/existing database was connected to or migrated. Test H2 is isolated. Default Hibernate physical naming yields users, charging_stations and booking with snake_case columns; inspect an existing database before applying SQL because an independently configured naming strategy may differ.

| Model area | Change | Data compatibility concern |
|---|---|---|
| users.name/email | NOT NULL, lengths 100/254; unique email constraint uk_users_email | Existing null/blank/oversized or duplicate normalized emails need owner-reviewed repair. |
| User.bookings | Initialized inverse collection; no cascade remove/orphan removal | History remains separately owned; deletion blocked when any booking references user. |
| charging_stations.station_name/location | NOT NULL, lengths 150/255 | Preflight current values; never silently truncate. |
| total_slots/avaialable_slots | NOT NULL; CHECK total > 0 and 0 <= available <= total | Existing invalid counters must be reconciled, not automatically overwritten. |
| booking.user_id/station_id | Existing nonnull FKs retained; user now LAZY, station remains LAZY | FK ownership unchanged; no cascade deletion. |
| booking.booking_status/amount | NOT NULL; CHECK amount > 0 and status in Confirmed/Cancelled | Existing unsupported/null status or nonpositive amount needs a business decision. The check is not a general currency/finite-number design. |
| booking.request_key | New nullable varchar(64), unique | Old bookings remain NULL; PostgreSQL permits multiple NULLs. No keys fabricated for old rows. |
| Booking indexes | idx_booking_user(user_id), idx_booking_station(station_id) | Nonunique indexes support scoped reads/history checks; inspect existing equivalents. |
| Queries | Locked user/station lookups; booking entity graphs, scoped lists, key lookup, history existence, scalar station lookup; station search | Lists remain unbounded. Substring search needs query-plan review at scale. |
| Transactions | Read-only services; explicit mutation transactions; consistent station lock for every inventory/status write | Locks require a transaction and all writers following the same protocol. Direct SQL writers must preserve it. |

The relationships remain User 1→many Booking and ChargingStation 1→many Booking through Booking-owned foreign keys. There is no connector table or time interval. No foreign-key cascade or data deletion was introduced. DTO mapping/fetch plans remove dependence on open-in-view.

### 7.2 Safe manual preflight for an existing PostgreSQL schema

These are review instructions, **not executed migration results**. Use a verified backup, restore rehearsal, isolated clone, maintenance plan and appropriate database owner. Confirm the actual database/schema, table names and current definitions first. Do not use the dev profile against production or rely on ddl-auto=update to retrofit constraints.

Start with read-only checks. They intentionally report candidate rows without choosing replacements or deleting duplicates:

~~~sql
-- Normalize candidates for review, not an automatic UPDATE.
SELECT id, email, lower(btrim(email)) AS proposed_email
FROM users
WHERE email IS NULL OR email <> lower(btrim(email));

-- Duplicate identity candidates under the intended normalized representation.
SELECT lower(btrim(email)) AS normalized_email, count(*)
FROM users
GROUP BY lower(btrim(email))
HAVING count(*) > 1;

SELECT id FROM users
WHERE name IS NULL OR btrim(name) = '' OR length(name) > 100
   OR email IS NULL OR btrim(email) = '' OR length(email) > 254;

SELECT id FROM charging_stations
WHERE station_name IS NULL OR btrim(station_name) = '' OR length(station_name) > 150
   OR location IS NULL OR btrim(location) = '' OR length(location) > 255
   OR total_slots IS NULL OR total_slots <= 0
   OR avaialable_slots IS NULL OR avaialable_slots < 0
   OR avaialable_slots > total_slots;

SELECT id FROM booking
WHERE booking_status IS NULL OR booking_status NOT IN ('Confirmed', 'Cancelled')
   OR amount IS NULL OR amount <= 0 OR user_id IS NULL OR station_id IS NULL;

SELECT b.id FROM booking b
LEFT JOIN users u ON u.id = b.user_id
LEFT JOIN charging_stations s ON s.id = b.station_id
WHERE u.id IS NULL OR s.id IS NULL;

-- Informational only: manual decrements/initial occupied slots mean disagreement
-- cannot safely be repaired by blindly equating these two quantities.
SELECT s.id, s.total_slots - s.avaialable_slots AS occupied_counter,
       count(b.id) FILTER (WHERE b.booking_status = 'Confirmed') AS confirmed_bookings
FROM charging_stations s
LEFT JOIN booking b ON b.station_id = s.id
GROUP BY s.id, s.total_slots, s.avaialable_slots;
~~~

Resolve all flagged data through an owner-approved plan preserving IDs, references and history. This task does not decide which duplicate account to merge/delete, which old status means cancellation, or which occupied counter is correct. Review email normalization **before** enabling uniqueness; PostgreSQL lower/btrim is a review aid, while Java uses strip and Locale.ROOT, so non-ASCII/locale-sensitive input needs explicit review. No bulk UPDATE or DELETE is supplied or executed here.

### 7.3 Reviewed schema changes after data preflight

Apply only missing definitions after inspecting existing constraints/indexes. The SQL below illustrates the intended schema, not an idempotent migration file. PostgreSQL ALTER TABLE can take locks: rehearse it and choose a maintenance window. Existing Hibernate-generated constraint names may differ; do not add redundant equivalents. Stop if any statement fails rather than bypassing a constraint.

~~~sql
BEGIN;

ALTER TABLE users ALTER COLUMN name TYPE varchar(100);
ALTER TABLE users ALTER COLUMN name SET NOT NULL;
ALTER TABLE users ALTER COLUMN email TYPE varchar(254);
ALTER TABLE users ALTER COLUMN email SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email);

ALTER TABLE charging_stations ALTER COLUMN station_name TYPE varchar(150);
ALTER TABLE charging_stations ALTER COLUMN station_name SET NOT NULL;
ALTER TABLE charging_stations ALTER COLUMN location TYPE varchar(255);
ALTER TABLE charging_stations ALTER COLUMN location SET NOT NULL;
ALTER TABLE charging_stations ALTER COLUMN total_slots SET NOT NULL;
ALTER TABLE charging_stations ALTER COLUMN avaialable_slots SET NOT NULL;
ALTER TABLE charging_stations ADD CONSTRAINT ck_station_inventory
    CHECK (total_slots > 0 AND avaialable_slots >= 0 AND avaialable_slots <= total_slots);

ALTER TABLE booking ADD COLUMN request_key varchar(64);
ALTER TABLE booking ADD CONSTRAINT uk_booking_request_key UNIQUE (request_key);
ALTER TABLE booking ALTER COLUMN booking_status SET NOT NULL;
ALTER TABLE booking ALTER COLUMN amount SET NOT NULL;
ALTER TABLE booking ADD CONSTRAINT ck_booking_state
    CHECK (amount > 0 AND booking_status IN ('Confirmed', 'Cancelled'));
CREATE INDEX idx_booking_user ON booking (user_id);
CREATE INDEX idx_booking_station ON booking (station_id);

COMMIT;
~~~

Existing user_id/station_id foreign keys and NOT NULL definitions were already part of the model. Confirm they reference users(id)/charging_stations(id) without delete cascade; add missing equivalents only after inspecting the real schema, not by guessing generated constraint names.

Because existing rows are not silently normalized, the plain email unique constraint requires the reviewed normalization step first. A future functional lower(email) index/check can protect direct SQL writers, but it is not falsely claimed as implemented by this entity mapping.

Rehearse startup with the default/prod validate profile on the migrated clone, inspect constraints/indexes explicitly and repeat lifecycle/concurrency tests on PostgreSQL. Hibernate validate alone does not validate every CHECK/unique/index rule. Keep a rollback/restore plan; dropping protection to make startup succeed is not the remedy. Flyway was not added because no trustworthy baseline/migration ownership for existing data was available; introducing it later requires that baseline strategy.

## 8. Java and Spring Boot Concepts Learned

### Dependency injection and strategy interfaces

Constructor injection already existed and was retained. Spring creates PaymentServiceImpl with one PaymentGateway selected by AppConfig. The service therefore asks an interface to pay rather than constructing StripeGateway itself. This is dependency inversion and inversion of control: Spring controls construction, while the class declares what it needs. Tests substitute an interface mock to produce true, false or an exception. Validated configuration now prevents a typo from choosing an accidental fallback.

### DTOs and Bean Validation

A User entity has persistence identity and relationships; UserRequestDTO has only the fields a client may supply. UserResponseDTO defines only the response fields. This separates persistence lifecycle from HTTP contracts. Java 17 records make these small immutable data carriers concise. Compact constructors strip fields before Bean Validation runs.

@NotNull and @Min solve different problems: missing totalSlots is not the same as zero. @AssertTrue checks the relationship between total and available. Service guards protect important internal entry points too. Database checks are a final layer, because controller validation cannot constrain every database writer. Unknown fields now fail rather than being silently ignored.

### Relationship ownership, lazy loading and fetch plans

Booking owns user_id/station_id via @JoinColumn. User.bookings is the inverse mappedBy side. Persisting the Booking with its references establishes the foreign keys; adding it to a list alone is not the owning database update. The list helper remains useful for consistent in-memory objects and now starts initialized.

LAZY relationships can be represented by Hibernate proxies that need a persistence context when accessed. Returning entities to Jackson couples HTTP rendering to those proxies. The updated service maps DTO fields inside a transaction; list/filter queries use entity graphs. open-in-view=false ensures the web response does not rely on an HTTP-wide Hibernate session. An entity graph is a query fetch plan, not a new relationship or a guarantee that every arbitrary query is efficient.

### Transactions, flush, commit and dirty checking

@Transactional on a public Spring service method is applied by a proxy. Calling the injected BookingService from controllers/tests goes through that proxy. One create/cancel transaction now covers booking state and station count. Runtime exceptions trigger rollback under Spring's default rules.

saveAndFlush sends SQL and detects many constraints before method return, but it is **not commit**. An outer transaction can still roll back the inserted booking and counter, as the rollback integration test demonstrates. A managed station does not need a redundant save merely to persist a changed field: Hibernate compares tracked state and issues UPDATE during flush/commit. This is dirty checking. Detached entities outside a transaction do not provide the same guarantee.

### Race conditions and database row locks

An availability check is a read, not a reservation. Two independent transactions can both read available=1 before either writes. PESSIMISTIC_WRITE protects the station row from competing writers until completion. Each mutation obeys the same lock rule, so the next transaction checks the committed result before reserving. User-before-station locking coordinates account deletion and makes create lock ordering consistent.

This serializes inventory per station rather than adding time-slot semantics. A CHECK enforces counter bounds, but bounds alone would not prove one booking per consumed slot; transaction/locking and state transitions provide the business invariant.

### Idempotency and external side effects

An idempotent operation repeats its outcome without repeating its business side effect. Repeated cancellation does not release twice. An optional request key lets supported booking retries return one persisted booking.

The database key alone does not guarantee exactly one external payment: two distinct-resource transactions can call a provider before one unique insert loses. Real payment design needs provider idempotency, persistent attempts, reconciliation and compensation/refund rules. The current immediate local mocks make the present transaction choice understandable without falsely solving distributed transactions.

### Transaction events and best-effort delivery

BookingConfirmedEvent separates successful persistence from a notification strategy. AFTER_COMMIT avoids confirmation for rollback. Catching sender runtime failure preserves the successful booking response. An in-memory event can still be lost on crash; durability is a different requirement and would justify an outbox only when actual delivery requirements exist.

### Exceptions as HTTP contracts

DomainConflictException describes a business conflict without making the service construct HTTP responses. GlobalExceptionHandler translates it to 409 and a consistent ApiError. Missing resources become 404; invalid requests 400; unexpected/provider/database failures are sanitized. ResponseEntityExceptionHandler keeps standard MVC behavior such as 405/Allow while sharing the error shape. This separates business responsibilities from transport formatting.

### Unit tests versus integration tests

Mockito unit tests prove decisions such as “reject duplicate email before saving.” MVC tests prove validation/status/serialization. Only the integrated transaction/database tests can exercise lock acquisition, rollback, CHECK/unique constraints and after-commit ordering. The held-payment test deliberately keeps the first transaction open while the second waits; the independent-connection test confirms commit visibility. Real HTTP tests exercise the embedded server and converter configuration beyond standalone MVC fixtures.

## 9. Testing Report

The final clean suite completed successfully on actual Java 17. Earlier failures/interrupted runs are retained below so the report does not erase unsuccessful verification attempts. Test existence alone is not treated as execution evidence.

### 9.1 Execution environment and command ledger

The baseline was attempted on installed JDK 21 while compiling release 17. An official checksum-verified Temurin 17.0.20.1+1 runtime was then obtained under the workspace work/runtime directory to execute the updated suite on the actual declared Java runtime. Maven wrapper remains 3.9.14. Runtime downloads/caches/logs are intermediate workspace artifacts, not application source or repository deliverables.

| Run | Exact command / environment | Observed result |
|---|---|---|
| Initial baseline bootstrap attempts | See command/cwd notes below | First failed during temporary-file rename/access; the retry with TEMP/TMP override failed because the workspace cwd had no POM. Neither executed tests. |
| Baseline original source | Baseline command/environment below | 1 test, 0 failures, 1 error; Unable to find a @SpringBootConfiguration from the original com.example.demo package. 3:12 minutes; completed 16:22:11 +05:30. No baseline successful build claim. |
| Updated early full runs | Same final Java 17 clean test command below | Two runs were interrupted while Mockito instrumentation attachment stalled; no completed suite result and no fabricated pass/fail counts. First used default Mockito plugins; second used subclass maker only. |
| Focused payment verification | Same wrapper/cache flags shown below, with -Dtest=PaymentServiceImplTest test instead of clean test | Actual Java 17: BUILD SUCCESS; 9 tests, 0 failures, 0 errors, 0 skipped; 17.550 seconds. |
| First completed updated full suite | Same Java 17 wrapper clean test command below, before the MVC fixture correction | Actual Java 17: 97 tests, 3 failures, 0 errors, 0 skipped. Integration class: 17/17 passed. Three standalone MVC fixture failures quoted String responses because the custom converter list contained Jackson only. |
| Final clean verification | See exact command/environment below | Actual Java 17: BUILD SUCCESS; 98 tests, 0 failures, 0 errors, 0 skipped; 1:47 minutes; completed 17 October 2026 at 16:34:19 +05:30. |
| Packaging after successful tests | Same Java 17 wrapper/cache flags with -DskipTests package | BUILD SUCCESS; 1:00 minute; completed 17 October 2026 at 16:35:51 +05:30. Spring Boot repackaged target/demo-0.0.1-SNAPSHOT.jar. This separate packaging command skipped tests; test evidence is the preceding clean run. |
| Diff/secret review | git diff --check; programmatic comparison of the historical password against current tracked/untracked deliverable files without printing its value | Diff check exit 0; historical password matches in current files: 0. The credential still exists in baseline history and needs rotation. |

The initial two baseline bootstrap attempts used the following command from the workspace root, first with the default temporary directory and then with TEMP/TMP set to workspace work/tmp:
~~~powershell
.\work\baseline\mvnw.cmd "-Dmaven.repo.local=$PWD\work\maven\repository" clean test
~~~

The actual baseline test was then executed from the work/baseline checkout with JAVA_HOME set to the installed JDK 21 path:
~~~powershell
$workspace = 'C:/Users/kshit/Documents/Codex/2026-10-17/codex-master-prompt-ev-charging-spring'
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.11'
$env:MAVEN_USER_HOME = "$workspace/work/maven"
$env:TEMP = "$workspace/work/tmp"
$env:TMP = "$workspace/work/tmp"
Set-Location "$workspace/work/baseline"
.\mvnw.cmd "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" clean test
~~~

The final suite ran from the repository directory with these exact environment values/command (PowerShell):

~~~powershell
$workspace = 'C:/Users/kshit/Documents/Codex/2026-10-17/codex-master-prompt-ev-charging-spring'
$env:JAVA_HOME = "$workspace/work/runtime/jdk17/jdk-17.0.20.1+1"
$env:MAVEN_USER_HOME = "$workspace/work/maven"
$env:TEMP = "$workspace/work/tmp"
$env:TMP = "$workspace/work/tmp"
Set-Location "$workspace/outputs/EV-Charging-Backend"
.\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" clean test
~~~

The focused and packaging invocations used the same environment/cache flags:
~~~powershell
.\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" -Dtest=PaymentServiceImplTest test
.\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" -DskipTests package
~~~

Observed final class counts:

| Class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| BookingIntegrationTest | 18 | 0 | 0 | 0 |
| AppConfigTest | 6 | 0 | 0 | 0 |
| BookingControllerTest | 6 | 0 | 0 | 0 |
| ChargingStationControllerTest | 10 | 0 | 0 | 0 |
| UserControllerTest | 9 | 0 | 0 | 0 |
| DemoApplicationTests | 1 | 0 | 0 | 0 |
| GlobalExceptionHandlerTest | 12 | 0 | 0 | 0 |
| BookingNotificationListenerTest | 4 | 0 | 0 | 0 |
| PaymentServiceImplTest | 9 | 0 | 0 | 0 |
| StationServiceImplTest | 15 | 0 | 0 | 0 |
| UserServiceImplTest | 8 | 0 | 0 | 0 |
| **Total** | **98** | **0** | **0** | **0** |

The three failed standalone expectations were not relaxed or removed. The fixture was corrected to preserve StringHttpMessageConverter alongside the strict Jackson converter, matching actual application response handling. An actual HTTP registration plain-text contract test was added to guard the behavior. Both Mockito mock-maker-subclass and member-accessor-reflection avoid the environment's unnecessary dynamic instrumentation attachment; all mocked collaborators are interfaces.

### 9.2 Test inventory and evidence boundaries

| Test class | What it exercises |
|---|---|
| DemoApplicationTests | Application configuration discovery with isolated test profile. |
| UserServiceImplTest | Email normalization/duplicates, locked updates, history-safe deletion, initialized bidirectional helper. |
| StationServiceImplTest | Counter validation, locked reduction, preserving occupied capacity, safe deletion and bounded search input. |
| PaymentServiceImplTest | Finite/positive amounts, success, decline, sanitized exception, explicit mock adapters. |
| UserControllerTest | Existing success text, validation, rejected IDs, bounded responses, update/delete and 404/409. |
| ChargingStationControllerTest | Existing success text/spelling, invalid/missing counters, 404/full 409, forbidden availability reset, search/delete. |
| BookingControllerTest | Create DTO/Location/header, positive request fields, 404, filters, cancellation query/status, invalid keys. |
| GlobalExceptionHandlerTest | Domain/MVC/database/payment/lock/unexpected statuses, field errors, redaction, preserved Allow header. |
| AppConfigTest | Both mock strategies, normalized selections and rejected invalid/real modes. |
| BookingNotificationListenerTest | Delegation, failure isolation, clear mock logging without provider/message contents. |
| BookingIntegrationTest | Real service transactions/JPA/constraints; lifecycle/filter/cancel; decline and outer rollback; after-commit visibility/failure; last/multiple-slot contention; held-lock wait; concurrent cancellation/retry; key reuse; safe deletion/resizing; real Boot DTO configuration; embedded HTTP; disabled multipart parser and registration text. |

The old test was relocated, not removed to obtain a green build. No application test requires PostgreSQL credentials or real gateway accounts. Failed/interrupted runs remain reported above; only the final completed clean result should be used for final pass counts.

### 9.3 External verification limitations

PostgreSQL 14 postgres/initdb executables on this Windows host failed even their --version invocation with Illegal System DLL Relocation. No PostgreSQL database was connected to or modified. H2 tests therefore do not establish PostgreSQL dialect, migration, query-plan or production lock scheduling behavior.

The integration suite started the embedded server on a random loopback port with isolated H2. runningEmbeddedServerAnswersHttpUsingTheTestDatabase checked a real station HTTP response; registration text and multipart HTTP tests exercised converter/parser behavior. This is actual local HTTP evidence, not a claim that the packaged JAR started against PostgreSQL. No PostgreSQL startup from the JAR was claimed.

Raw intermediate logs remain in workspace work/: baseline-test-executed.log, updated-test-first.log and updated-test-subclass.log document the historical attempts, and updated-test-verified.log records the final suite. Test reports are generated under target/surefire-reports; build/log artifacts are not source deliverables and are excluded from the clean source archive. Exact final publication/commit state is given in the task summary.

Real provider delivery/payments, public authentication, production infrastructure, existing-data migration, large-scale performance, exhaustive security scanning and all possible concurrency schedules remain unverified. These are not claimed as working capabilities.

## 10. Security Review

### Committed credential and remediation

A literal database password exists in the baseline application.properties and its reachable Git history. Its value is intentionally absent from this report, current configuration and logs. Treat it as exposed even if it was originally intended only for local development: determine where it was used and rotate that credential. Removing the current line does not revoke old access or erase old commits.

Current configuration uses environment variables and .env.example contains placeholders only. .gitignore continues to exclude real environment files and allows the example specifically. No history rewrite, force-push or production credential change was performed. History cleanup requires a controlled separately authorized plan coordinating branches/clones/forks and collaborators after rotation; changing history is not a substitute for rotation.

### API boundary and data exposure

DTOs reject entity identities/relationships, validation guards bad state, database constraints protect persistence, user read responses omit booking graphs, and error/log handling avoids SQL/stack/provider detail. Payment adapters make no external call, and mock senders never log the message content. No permissive CORS rule or new password/payment key was introduced.

There is still no authentication, authorization, role model, resource ownership or rate limiting. Any local client able to reach the port can list personal data, create/cancel bookings, mutate capacity and delete unreferenced records. Default 127.0.0.1 binding and absent CORS permissions do not authorize users; CORS is not a server-side access control. SERVER_ADDRESS can expose the API. The prod profile only changes schema defaults and does not secure business operations. Keep it private until an identity/role/ownership design is implemented with tests.

### Bounded dependency/advisory review

Boot 3.5.3 dependency management retains Spring Framework 6.2.8 and Tomcat 10.1.42. The task preserved the requested platform versions; this is not a claim that they are current or free from vulnerabilities. The advisory review was bounded, not exhaustive dependency scanning.

| Advisory / source | Relevance to this code and action |
|---|---|
| [Tomcat security advisories, including CVE-2025-61795](https://tomcat.apache.org/security-10.html) | Multipart temporary-file DoS includes Tomcat through 10.1.46; fixed in 10.1.47. This version is in range. No upload API exists; unused multipart parsing was disabled and HTTP rejection tested. A compatible tested Boot BOM patch remains required; mitigation is not an upgrade or exploit proof. |
| [Spring CVE-2025-41249](https://spring.io/security/cve-2025-41249/) | Published method-security conditions require a feature such as @EnableMethodSecurity, which this project does not enable. That does not make the anonymous API protected. |
| [Spring CVE-2025-41242](https://spring.io/security/cve-2025-41242/) | Official advisory states default Tomcat configuration is not vulnerable under its described conditions. Reassess if deployment/resource-handler configuration changes. |
| [Spring CVE-2026-47834](https://spring.io/security/cve-2026-47834/) | The described untrusted Sort/native-query path is absent in the inspected repositories. No arbitrary user Sort or native query was introduced. |

HTTP/2 is not enabled by current configuration; reassess applicable Tomcat denial-of-service advisories before enabling it. The required next security milestone is a tested compatible Boot 3.5.x dependency-management patch before broader deployment, plus the API security model. No blanket “secure” or “production-ready” claim is made.

### Remaining security/configuration work

Rotate the exposed database credential; review who could have accessed history; use least-privileged database roles; migrate a backed-up schema safely; update vulnerable dependency ranges; design authentication/ownership for each endpoint before exposing it. Real payments additionally need provider secrets, verification/webhook authenticity and durable idempotency/reconciliation. Do not place real card, payment or message credentials in source or test fixtures.

## 11. Remaining Work

### Required work

| Priority | Work | Completion condition |
|---|---|---|
| P0 | Rotate the exposed DB credential and review reachable-history exposure. | Old credential revoked wherever used; controlled history-remediation plan completed if required. No value copied into reports. |
| P1, before broader deployment | Patch the retained dependency-management line through a tested compatible Boot 3.5.x update. | Review official advisories, run tests and PostgreSQL verification against the selected patched BOM. Do not treat disabled multipart as a full dependency remediation. |
| P1, before untrusted access | Decide identities/roles/ownership and implement authentication/authorization. | Specify who can register/list/update/delete users; administer stations; book/cancel/view another user's bookings; operate manual capacity. Add negative access-control tests and deployment/TLS configuration. |
| P1, before existing-data startup | Rehearse the manual PostgreSQL preflight/migration on a restored clone. | Resolve dirty rows with the owner, preserve history, install/inspect constraints/indexes and prove validate startup. |
| P1, before production confidence | Execute transaction/concurrency tests against PostgreSQL. | Resolve the host PostgreSQL executable blocker or use an isolated supported environment; verify lock waits, rollback, retry key conflicts and migration behavior. |
| P1, only if real payments are required | Define pricing/currency/payment states, provider idempotency, verification, webhook authenticity, refunds and reconciliation before implementing a provider. | Durable tested sandbox flow; no claim that current mocks process money. Never simply replace the immediate mock call with slow network I/O under the station lock. |

### Recommended improvements

| Priority | Improvement | Reason |
|---|---|---|
| P2 | Decide manual capacity policy and booking completion/release rules. | Current reduceSlot can consume capacity without a Booking. Completion, maintenance and repair cannot safely be inferred from Cancelled alone. |
| P2 | Paginate user/station/booking lists and search; define stable ordering. | Existing all endpoints preserve compatibility but grow without bounds. |
| P2 | Baseline reviewed schema with migration tooling after the manual rehearsal. | Flyway is useful once ownership/baseline strategy is explicit; adding it without one risks existing data. |
| P2 | Define account retention/anonymization policy. | Deletion currently preserves all booking history; privacy and retention requirements need deliberate design. |
| P2 | Improve operational diagnostics, metrics and rate/size/time limits for deployment. | Sanitized client errors and type-only logs protect information but are not a complete operational system. |
| P2, if real delivery is required | Add real recipient/provider configuration, durable notification retry/outbox and delivery status. | Current synchronous after-commit local event is intentionally best-effort. |

### Optional future enhancements requiring domain decisions

P3 time reservations require answers before code: what is a charging resource, how many connectors can work simultaneously, are intervals half-open, which statuses occupy capacity, what timezone/lead time/duration rules apply, and what happens at completion/no-show? Only then choose overlap queries/database constraints and concurrent tests. Do not retrofit times onto aggregate counts while claiming double-booking is solved for connectors.

P3 geospatial discovery, connector/power types, price schedules, reports and richer status transitions can follow actual product requirements. Microservices, Kafka, Redis and Kubernetes remain unjustified for this educational monolith.

Recommended next milestone: rotate the credential, establish a patched dependency baseline and run the existing suite/migration on isolated PostgreSQL, then write the small role/ownership specification before opening network access. Real integrations and scheduling can follow those foundations.

## 12. How to Run the Updated Project

### 12.1 Windows prerequisites and Java/Maven

Use a JDK 17 installation, a supported PostgreSQL service for application startup, and network access for the first wrapper/dependency download. The checked-in wrapper selects Maven 3.9.14; a separate Maven install is unnecessary. Java 17 and Boot 3.5.3 are still the project versions. The verification runtime details are in Section 9.

Open PowerShell in the repository. Replace these local path placeholders with your installed/cloned locations:

~~~powershell
Set-Location 'C:/path/to/EV-Charging-Backend'
$env:JAVA_HOME = 'C:/Tools/jdk-17'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
java -version
./mvnw.cmd -version
~~~

Expect Java 17 and Maven 3.9.14 in version output. JAVA_HOME must point at the JDK root, not its bin directory. The repository's package names do not require changing the POM group/name as part of this task.

### 12.2 Run isolated tests first

~~~powershell
./mvnw.cmd clean test
~~~

Tests activate their test profile and use in-memory H2; no PostgreSQL server, gateway credentials or real notification provider is needed. The DB is created/dropped for tests. The final observed result for this source snapshot is in Section 9; future edits must be tested again rather than assuming that result persists.

If dependencies must be stored in a workspace cache, an optional equivalent is:
~~~powershell
./mvnw.cmd --batch-mode --no-transfer-progress '-Dmaven.repo.local=C:/path/to/cache/repository' clean test
~~~

Do not point a test profile at existing application data.

### 12.3 New isolated PostgreSQL development database

Use an existing authorized PostgreSQL installation or start one in an isolated development environment. Have its administrator create a dedicated database/user, for example evcharging_audit_dev and an application role allowed to create the development schema. Choose a database name that does not already contain important data. No application command below creates/drops a database or repairs old records.

The current environment's PostgreSQL executable blocker is documented in Section 9; these instructions do not assert that service was running here.

Export configuration in the same PowerShell process. All values below are placeholders except nonsecret development defaults:

~~~powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/evcharging_audit_dev'
$env:DB_USERNAME = 'your_local_application_role'
$env:DB_PASSWORD = 'replace_with_your_local_database_password'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8081'
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:PAYMENT_MODE = 'mock'
$env:PAYMENT_GATEWAY = 'stripe'
$env:NOTIFICATION_MODE = 'mock'
$env:NOTIFICATION_TYPE = 'email'
./mvnw.cmd spring-boot:run
~~~

The dev profile enables Hibernate update only for this isolated development schema. Either stripe/razorpay and email/sms are supported mock selections; unknown selections and non-mock modes fail startup. The application defaults to loopback port 8081. Expected successful startup includes the Boot started message and one exposed Actuator health endpoint, but a real PostgreSQL startup still requires the configured service/credentials/schema.

.env.example is a reference template; Spring Boot does not automatically load a copied .env file. Export variables or configure an approved local launcher, and never commit actual values. For an existing reviewed schema, apply Section 7 manually first, then use the default or prod profile with validate. Do not enable update to bypass migration errors. The prod profile does not implement authentication.

### 12.4 Example request sequence in a second PowerShell window

These are expected API behaviors on a fresh isolated database. They are instructions, not fabricated PostgreSQL execution evidence.

~~~powershell
$base = 'http://127.0.0.1:8081'
Invoke-RestMethod -Method Post -Uri "$base/users/reg" -ContentType 'application/json' -Body (@{name='Ada';email='ada@example.com'} | ConvertTo-Json)
Invoke-RestMethod -Method Post -Uri "$base/station/reg" -ContentType 'application/json' -Body (@{stationName='Learning EV';location='Pune';totalSlots=2;avaialableSlots=2} | ConvertTo-Json)
$user = (Invoke-RestMethod "$base/users/all" | Where-Object email -eq 'ada@example.com' | Select-Object -First 1)
$station = (Invoke-RestMethod "$base/station/search?query=Learning" | Select-Object -First 1)
$request = @{userId=$user.id;stationId=$station.id;amount=1} | ConvertTo-Json
$headers = @{'Idempotency-Key'='learning-request-001'}
$booking = Invoke-RestMethod -Method Post -Uri "$base/booking/createBooking" -Headers $headers -ContentType 'application/json' -Body $request
$booking
Invoke-RestMethod "$base/station/$($station.id)"
Invoke-RestMethod -Method Post -Uri "$base/booking/createBooking" -Headers $headers -ContentType 'application/json' -Body $request
Invoke-RestMethod -Method Put -Uri "$base/booking/cancel?id=$($booking.bookingId)"
Invoke-RestMethod -Method Put -Uri "$base/booking/cancel?id=$($booking.bookingId)"
Invoke-RestMethod "$base/booking/$($booking.bookingId)"
Invoke-RestMethod "$base/station/$($station.id)"
~~~

Expected outputs: registration text “User Registered Successfully” and “Station added successfully”; create returns a Confirmed DTO with amount 500 despite input amount 1; available drops 2→1; replay returns the same booking ID without another decrement; two cancels leave Cancelled and available returns to 2 once. With the same key after cancellation, create replays Cancelled instead of booking again. Repeated registration with the email yields 409. Deleting that user/station after cancellation still yields 409 because booking history exists.

Use Stop/Ctrl+C in the startup terminal to stop the local app. Optional executable packaging is:
~~~powershell
./mvnw.cmd clean package
java -jar ./target/demo-0.0.1-SNAPSHOT.jar
~~~

Packaging normally runs tests. Use the same environment variables when starting the JAR. No release publication, merge or deployment is performed by these instructions.

### 12.5 Review and publish the dedicated Git branch

Changes are prepared locally on codex/backend-audit-improvements. The final task summary identifies the local commit; no push, pull request, merge or GitHub release was performed. Review the committed baseline difference with these PowerShell commands:

~~~powershell
Set-Location 'C:/Users/kshit/Documents/Codex/2026-10-17/codex-master-prompt-ev-charging-spring/outputs/EV-Charging-Backend'
git status --short
git branch --show-current
git log -1 --oneline
git diff --stat 47872bf..HEAD
git diff 47872bf..HEAD -- src/main/java src/test pom.xml docs .gitignore .env.example
~~~

The focused source review command intentionally excludes the historical application.properties password line; inspect current safe configuration and the documented changes without copying the old credential into public logs. Review the remaining schema/security actions above, rotate the exposed credential and complete the controlled history decision before publishing. The ordinary non-force publication command, when that review is complete and credentials permit, is:

~~~powershell
git push -u origin codex/backend-audit-improvements
~~~

This publishes the development branch; it does not merge main or create a release. A pull request can then be opened from this branch through the repository UI. Do not overwrite main or force-push as part of these task instructions.

## 13. Learning Walkthrough

### 13.1 Registering and retrieving a user

The flow is HTTP JSON → UserController.registerUser → UserRequestDTO validation/toEntity → UserServiceImpl.registerUser → UserRepository → users table → existing text response.

1. Jackson accepts name/email only. The compact record constructor strips whitespace and lowercases email with Locale.ROOT.
2. @Valid rejects a blank name or malformed email before the service runs.
3. The transactional service checks normalized duplicates for a helpful conflict, then saveAndFlush invokes the database constraint too. Two callers can both pass an existence check, but both cannot commit the same normalized unique email.
4. A read follows UserController.getUserById → UserServiceImpl → UserRepository → UserResponseDTO.from. Only id/name/email are copied; bookings are not traversed or returned.

This demonstrates why a DTO, service validation and a database constraint have different responsibilities rather than being interchangeable.

### 13.2 Booking the last available aggregate slot

The updated path is:

~~~mermaid
sequenceDiagram
    participant C as HTTP client
    participant BC as BookingController
    participant BS as BookingService proxy/implementation
    participant DB as Repositories/PostgreSQL
    participant P as Mock PaymentService
    participant N as After-commit listener
    C->>BC: POST createBooking + optional request key
    BC->>BS: validated positive IDs
    BS->>DB: begin; lock user, then station
    BS->>DB: check stored request key and current capacity
    BS->>P: processPayment(500), local mock only
    BS->>DB: insert Confirmed booking + decrement managed station
    BS->>DB: flush, then commit
    BS->>N: committed event callback
    N-->>N: mock send; isolate runtime failure
    BS-->>BC: BookingResponseDTO
    BC-->>C: 201 + Location + JSON
~~~

Consider two users booking one remaining slot:

| Moment | Request A | Request B |
|---|---|---|
| 1 | Locks its user, then the station; sees available=1. | Locks its user; waits for the same station row. |
| 2 | Local mock accepts 500; saves Confirmed and available=0. | Cannot run the protected capacity decision yet. |
| 3 | Commits; releases row lock. | Acquires station; reads available=0. |
| 4 | Receives created DTO. | Receives 409; no second mock payment/reservation. |

Originally both could read 1 before a separately saved decrement. The lock changes when the second caller is allowed to make that decision. The CHECK is a backstop for counters; the transaction makes booking/counter atomic. The held-payment integration test keeps A inside the transaction while proving B does not finish early. This evidence is H2-backed, with PostgreSQL verification still required.

### 13.3 Rollback, events and real-payment limitations

saveAndFlush is not the success boundary. An outer transaction can mark rollback-only after createBooking returns: the booking and decrement disappear and the AFTER_COMMIT callback does not execute. The integration rollback test does precisely this.

Once commit succeeds, the listener is allowed to notify. Its runtime failure is caught because data is already committed. An independent connection in the ordering test observes the committed booking when the sender callback runs. There is no durable delivery guarantee if the process crashes.

The local mock can safely live inside this learning transaction because it is immediate and moves no money. A real provider cannot be “rolled back” with SQL. Provider idempotency/reconciliation and a durable payment workflow must be designed before replacing the mock.

### 13.4 Retrying creation and cancellation

With the same user/station/key, a second create waits for the shared locks, finds request_key and returns the stored DTO before capacity/payment. If the booking has since been cancelled, the response stays Cancelled. A key belongs to one resource request, not to permission to create another booking.

On cancel, BookingRepository first returns only station ID. Loading a full booking before waiting could cache Confirmed in the persistence context. The service locks the station and only then loads Booking. Caller one changes Confirmed→Cancelled and adds one available slot. Caller two sees Cancelled after the lock and returns without incrementing. Dirty checking commits both changes as one transaction.

### 13.5 Resizing a station and preserving history

A station with total=5 and available=2 has occupied=3. Updating total to 7 calculates available=7−3=4; it does not reset to 7. Updating total to 2 conflicts because three occupied slots cannot fit. The update DTO cannot include avaialableSlots, and it takes the same station lock as create/cancel/manual reduce.

Deleting a user/station with even a Cancelled booking conflicts. This explicit history rule avoids hiding cascade deletion behind a convenience endpoint. A future retention/anonymization policy should be implemented deliberately rather than weakening the foreign keys.

## 14. Final Summary and Completion Checklist

The backend remains a small Spring Boot monolith with its original versions/domain. The main correctness improvement is a shared transactional lock protocol for aggregate inventory, booking creation, cancellation and station changes. DTOs, validation, database constraints and consistent errors make the HTTP/persistence boundaries explicit. Useful CRUD/search/filter operations were added without inventing scheduling or live integrations.

The final automated evidence is in Section 9. It covers the implemented local/H2-backed behavior and actual embedded HTTP tests; it does not prove a production PostgreSQL installation, migration or real provider operation.

| Item | State |
|---|---|
| Complete original source/config/test audit | Completed; baseline and decisions in INITIAL_CODE_AUDIT.md. |
| Confirmed P0/P1 source fixes | Implemented; exposed credential removed from current source, concurrency/transaction/cancellation/validation failures addressed. Rotation remains manual. |
| Useful P2 improvements | DTO/error contracts, safe updates/deletes, search/filters, explicit mock modes/configuration and regression tests implemented. |
| Automated tests | Added and executed; exact commands/history/final counts in Section 9. No failing requirement removed. |
| Java/Boot versions | Preserved Java 17/Boot 3.5.3; runtime evidence distinguished from compile target. Dependency patch remains required. |
| Database/data | PostgreSQL retained; isolated H2 tests; no existing data migrated/deleted. Reviewed manual SQL/preflight supplied. |
| Payment/notification behavior | Explicit development mocks; after-commit best-effort notification; no live charge/message. |
| Mandatory documentation | Initial audit and this comprehensive report written into docs/. |
| Git isolation | Dedicated codex/backend-audit-improvements branch; final commit/publication state should be checked in the task summary. No main overwrite, force-push, merge or release is authorized by this report. |
| Outstanding environment blocker | Local PostgreSQL executables failed before connection; real PostgreSQL verification remains required. |
| Manual actions | Rotate credential; controlled history remediation; reviewed migration; compatible dependency patch; security model before public access. |

Next milestone: prove this implementation against isolated PostgreSQL and a patched dependency baseline, then design/test endpoint ownership and roles. Connector/time reservations, live providers and durable delivery should follow explicit product decisions rather than be inferred from class names.
