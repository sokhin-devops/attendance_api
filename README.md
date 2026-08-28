# Attendance API

Backend for a multi-tenant employee attendance platform: GPS-geofenced check-in/out, leave
requests with balance tracking, in-app + push notifications, and attendance analytics.

Spring Boot 3.5 · Java 17 · PostgreSQL · Flyway · Spring Security (JWT) · springdoc-openapi

---

## Quick start

### 1. Database

Either point at an existing PostgreSQL 15+ instance, or start one with compose:

```bash
docker compose up -d postgres
```

That creates `attendance_db` (user `postgres`, password `underadmin`) and also
`attendance_test_db`, which the integration tests use. Against a pre-existing server, create
both databases yourself:

```bash
psql -h localhost -U postgres -c "CREATE DATABASE attendance_db;"
psql -h localhost -U postgres -c "CREATE DATABASE attendance_test_db;"
```

Flyway applies the schema on first start — no manual DDL.

### 2. Run

```bash
mvn spring-boot:run
```

The API listens on **http://localhost:8081**.

- Swagger UI — http://localhost:8081/swagger-ui.html
- OpenAPI JSON — http://localhost:8081/v3/api-docs

### 3. Sign in

On an empty database the app creates a platform super admin and logs the credentials at
startup:

```
email:    superadmin@attendance.local
password: SuperAdmin@123
```

Change that password and set `APP_SEED_ENABLED=false` outside development.

```bash
curl -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"superadmin@attendance.local","password":"SuperAdmin@123"}'
```

Send the returned `accessToken` as `Authorization: Bearer <token>` on every other endpoint.

### Running the whole stack in Docker

```bash
docker compose up --build api
```

---

## Configuration

Every setting has a working default for local development; override via environment variable.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/attendance_db` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `underadmin` | Database credentials |
| `APP_JWT_SECRET` | dev-only placeholder | HS256 signing key, **min 32 bytes** |
| `APP_JWT_ACCESS_MINUTES` | `15` | Access-token lifetime |
| `APP_JWT_REFRESH_DAYS` | `7` | Refresh-token lifetime |
| `APP_CORS_ORIGINS` | `http://localhost:4200,…` | Comma-separated allowed origins |
| `APP_SEED_ENABLED` | `true` | Create the bootstrap super admin when no platform user exists |
| `APP_SEED_EMAIL` / `APP_SEED_PASSWORD` | see above | Bootstrap credentials |
| `APP_FCM_ENABLED` | `false` | When false, push payloads are logged instead of sent |

---

## Roles

| Role | Scope | Can do |
| --- | --- | --- |
| `SUPER_ADMIN` | Platform (no organization) | List/create/deactivate organizations; may target any tenant by id |
| `ORG_ADMIN` | One organization | Users, locations, leave policy, manual attendance override, all reports |
| `MANAGER` | Direct reports | Team attendance and reports; approve/reject their reports' leave. **Cannot** override attendance |
| `EMPLOYEE` | Self | Check in/out, own history, own balances, submit leave |

### Tenant isolation

Every tenant-scoped query filters on `organization_id`, and `AccessControlService` is the
single gate that decides which organization and which employees a caller may touch. Knowing
another tenant's ids does not help: a cross-tenant organization or report request returns
**403**, and a cross-tenant record lookup returns **404** rather than confirming the row
exists. `TenantIsolationIntegrationTest` asserts this across every resource.

Email is unique **per organization**, so the same address may exist in several tenants. When
it does, login requires a `tenantKey`; otherwise email alone is enough.

---

## API overview

Base path `/api/v1`. All list endpoints are paginated (`page`, `size`, default 20, max 100)
and return `{ data, totalElements, totalPages, currentPage, pageSize, hasNext }`.

**Auth** (public: login, refresh, register-organization)
```
POST   /auth/login                 POST /auth/refresh        POST /auth/logout
POST   /auth/register-organization  GET  /auth/me             POST /auth/change-password
POST   /auth/devices               DELETE /auth/devices
```

**Organizations & users**
```
GET    /organizations                                   (SUPER_ADMIN)
POST   /organizations                                   (SUPER_ADMIN)
GET    /organizations/{orgId}
PUT    /organizations/{orgId}
DELETE /organizations/{orgId}                           (SUPER_ADMIN)
GET    /organizations/{orgId}/users
POST   /organizations/{orgId}/users
GET    /organizations/{orgId}/users/{userId}
PUT    /organizations/{orgId}/users/{userId}
DELETE /organizations/{orgId}/users/{userId}
GET    /organizations/{orgId}/users/{userId}/team
GET    /organizations/{orgId}/users/{userId}/locations
PUT    /organizations/{orgId}/users/{userId}/locations
```

**Locations**
```
GET    /organizations/{orgId}/locations
POST   /organizations/{orgId}/locations
GET    /organizations/{orgId}/locations/{locationId}
PUT    /organizations/{orgId}/locations/{locationId}
DELETE /organizations/{orgId}/locations/{locationId}
```

**Attendance**
```
POST   /attendance/check-in        POST /attendance/check-out
GET    /attendance/status          GET  /attendance/my-locations
GET    /attendance/me              GET  /attendance          GET /attendance/{id}
PUT    /organizations/{orgId}/attendance/override        (ORG_ADMIN)
```

**Leave**
```
POST   /leave-requests             GET  /leave-requests      GET /leave-requests/me
GET    /leave-requests/{id}
PUT    /leave-requests/{id}/approve   /reject   /cancel
GET    /leave-balances/me          GET  /leave-balances/{userId}
PUT    /organizations/{orgId}/leave-balances/{userId}/{leaveType}
POST   /organizations/{orgId}/leave-balances/rollover
```

**Notifications**
```
GET    /notifications              GET  /notifications/unread-count
PUT    /notifications/{id}/read    PUT  /notifications/read-all
DELETE /notifications/{id}         DELETE /notifications/purge
```

**Reports** (scope follows the caller's role)
```
GET    /organizations/{orgId}/dashboard/summary
GET    /organizations/{orgId}/reports/attendance[.csv]
GET    /organizations/{orgId}/reports/lateness[.csv]
GET    /organizations/{orgId}/reports/leave[.csv]
```

---

## How the domain behaves

**Geofencing.** Check-in compares the device coordinates against the location's
latitude/longitude and radius using great-circle (haversine) distance. Outside the radius the
request is refused with **403** and a body reporting the actual distance and the allowed
radius. If the organization sets `allowManualCheckIn` *and* the request carries a
`manualReason`, the check-in is instead accepted and flagged as a manual override. Employees
may only check in at locations assigned to them.

**Timezones.** "Today" and lateness are resolved in the *organization's* timezone, not the
server's, so tenants in different regions each get their own working day. An unparseable
timezone falls back to UTC with a warning rather than failing the request.

**One record per day.** `attendance_records` is unique on `(user_id, work_date)`. A second
check-in is a **409**; an admin override amends the existing row instead of adding one.

**Leave approval.** A request routes to the employee's manager, or to every org admin when
they have none. Managers may decide only for direct reports; org admins for anyone in the
tenant; nobody may decide their own request. Balance is checked at submission (so the employee
learns early) and debited at approval — an insufficient balance aborts the approval rather
than leaving an inconsistent state. `UNPAID` and `OTHER` are tracked but never capped. An org
admin cancelling an approved request credits the days back.

**Reports.** Each Mon–Fri day resolves to exactly one of present / late / on-leave / absent
per employee. Approved leave counts as leave, never absence. A late day counts as both present
and late.

**Notifications.** In-app rows are written inside the originating transaction, so a failed
operation never leaves an orphaned notice. Push delivery goes through
`PushNotificationService`; with `APP_FCM_ENABLED=false` the payload is logged instead of sent,
which exercises the whole path without Firebase credentials. Wiring real FCM means replacing
the body of `dispatch(...)` — nothing else changes.

**Safety rails.** An organization must always retain one active `ORG_ADMIN`; you cannot
deactivate your own account; users and locations are deactivated rather than deleted so
history survives; changing a password revokes every refresh token.

---

## Tests

```bash
mvn test
```

96 tests: unit tests for the geofence maths, inclusive leave-day counting and
timezone-sensitive lateness, plus HTTP-level integration tests that go through the real
security filter chain with real bearer tokens.

| Suite | Covers |
| --- | --- |
| `AuthIntegrationTest` | Login, ambiguous-email tenant resolution, refresh rotation, logout, signup, password change |
| `AttendanceIntegrationTest` | Geofence accept/reject and boundary, manual override path, duplicate check-in, visibility by role, admin override |
| `LeaveIntegrationTest` | Approval routing, balance debit/credit, decision authority, cancellation, rollover |
| `TenantIsolationIntegrationTest` | Cross-tenant reads and writes across every resource |

Integration tests need `attendance_test_db` to exist; they reset tenant data before each test
and never touch `attendance_db`.

---

## Project layout

```
src/main/java/com/attendance/api/
├── config/       Security, CORS, OpenAPI, properties, bootstrap seeder
├── security/     JWT provider, auth filter, principal, 401/403 handlers
├── domain/       JPA entities + enums
├── repository/   Spring Data repositories (+ QueryParams for optional filters)
├── dto/          Request/response records, grouped by resource
├── service/      Business logic; AccessControlService is the authorization gate
├── controller/   REST endpoints, annotated for Swagger
└── exception/    Typed exceptions + GlobalExceptionHandler
src/main/resources/db/migration/   Flyway migrations
```

### Null safety

The build is clean under `javac -Xlint:all` **and** under Eclipse JDT's annotation-based null
analysis, which is what the VS Code Java extension runs (`java.compile.nullAnalysis.mode` is
set to `automatic` in `.vscode/settings.json`). Two pieces make that work:

- `lombok.config` sets `lombok.addNullAnnotations = spring`, so Lombok-generated builders,
  getters and setters carry `@NonNull`/`@Nullable`. Without it every `Entity.builder()...build()`
  passed into Spring Data — whose packages are `@NonNullApi` — is reported as an unchecked
  conversion, which was the bulk of the original warnings.
- Nullability is stated where it is a real domain rule: `@Nullable` on the associations that
  genuinely can be absent (`User.organization` is null only for `SUPER_ADMIN`,
  `LeaveRequest.approvedBy` only exists after a decision), and `@NonNull` on the
  resolve-or-throw helpers in `SecurityUtils` and `AccessControlService`.

`Require.found(...)` / `Require.present(...)` replace `Optional.orElseThrow(...)` in the
resolve-or-404 helpers. The JDK is not null-annotated, so `orElseThrow` returns a value of
unknown nullity; `Require` does the same job with an explicit check that flow analysis can
verify, which keeps the non-null guarantee intact for callers.

**If VS Code reports `cannot find symbol: method getEmail()`** (or any other
Lombok-generated accessor) while `mvn compile` succeeds, the Java language server is holding
a stale view of `lombok.config`. Lombok caches its config per directory, so a newly added or
edited `lombok.config` needs a server restart to take effect:

> Command Palette → **Java: Clean Java Language Server Workspace** → *Reload and delete*

The extension bundles its own Lombok (1.18.39 at the time of writing) separate from the
version Maven resolves; both support the `addNullAnnotations` key used here, so a mismatch
is not the cause — only the cache is.

If you would rather not run the IDE analysis at all, set
`"java.compile.nullAnalysis.mode": "disabled"` — nothing in the Maven build depends on it.

### A note on optional query filters

`QueryParams` normalises optional filters into values that always bind. PostgreSQL cannot
resolve `lower(?)` when a String parameter arrives as an untyped null (it infers `bytea`),
cannot always type a bare `? IS NULL`, and Hibernate cannot expand a null collection into an
`IN` list. Rather than scattering casts through the JPQL, optional filters become LIKE
patterns, wide date bounds, non-empty id lists, or companion boolean flags.

---

## Error format

Every non-2xx response uses one shape:

```json
{
  "timestamp": "2026-08-27T11:40:25.610Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/api/v1/organizations/{id}/locations",
  "fieldErrors": { "name": "must not be blank" }
}
```

`400` validation or domain-rule violation · `401` missing/expired/invalid token ·
`403` wrong role, wrong tenant, or outside geofence · `404` not found (or not visible to
this tenant) · `409` conflict with existing state.

A geofence rejection carries the measurement in `fieldErrors`:

```json
{ "status": 403, "error": "Outside Geofence",
  "fieldErrors": { "distanceMeters": "1033000.3", "allowedRadiusMeters": "150" } }
```

---

## Scheduled jobs

| Job | Schedule | Purpose |
| --- | --- | --- |
| Check-out reminder | hourly at :05 | Notifies anyone still checked in once their org's local end-of-day passes |
| Notification purge | 02:30 daily | Drops notices past `app.notifications.retention-days` (90) |
| Refresh-token purge | 02:45 daily | Deletes tokens that can no longer be redeemed |
