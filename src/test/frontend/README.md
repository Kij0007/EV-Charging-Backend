# Frontend API tests

The website has no frontend package installation or build step. Spring Boot serves
the HTML, CSS and JavaScript from `src/main/resources/static`.

Run these API-client unit tests from the repository root with Node.js 22 or later:

```powershell
node --test src/test/frontend/api.test.mjs src/test/frontend/reservation-feedback.test.mjs src/test/frontend/public-demo.test.mjs
```

The tests use Node's built-in test runner and replace the network boundary with
controlled responses. They verify the actual browser API module's CSRF token
handling, same-origin credentials, idempotency header forwarding, session-expiry
events, empty responses and safe errors. Reservation-feedback tests verify that
cancelled/completed idempotent replays never claim an active reserved space.
The three status-feedback regressions cover confirmed, cancelled and completed
reservations. Nine API-client cases include the short timeout for optional site
information. Five public-demo cases verify mode selection, honest demo copy and
unchanged normal-mode content. There are 17 tests in total.
They do not simulate the application
database or prove that a real browser can complete a reservation. Backend tests
and the documented real-browser checks cover those separate layers.

JavaScript syntax can also be checked without starting the server:

```powershell
node --check src/main/resources/static/assets/app.js
node --check src/main/resources/static/assets/api.js
node --check src/main/resources/static/assets/content.js
```

All actions in the production frontend call the Spring application. There are no
browser-only fake reservations, hardcoded station datasets or mock login flows.
