# Spec: REST docs convention (gluon TD-20.10)

## Overview
user-service `/docs` shows bare paths and unexplained schemas. Apply the
TD-20 convention (purerest README "Documenting endpoints"), mirroring
order-service `rest-docs_20261101`, and assert it in a test. Auth endpoints
must never show a real credential or token in an example.

## Functional Requirements
- FR1: purerestlib 0.4.0 → 0.7.3 (+ aligned pins); suite green;
  tech-stack.md synced.
- FR2: Per-endpoint error outputs — already narrowed by TD-16.7: signup
  400 invalid email, 400 password too short, 409 duplicate email; login
  400 invalid email, 400 password missing, 401 invalid credentials;
  health 503. Pin with a `UserDocsSuite` test walking each endpoint's
  `errorOutput` statuses.
- FR3: Every served endpoint `.summary`/`.description`/`.tag` (`auth`,
  `health`) — see Appendix.
- FR4: Every path/query/header parameter described + example — none exist
  on the served endpoints (bodies only).
- FR5: Every request/response DTO + field `@description`; each scalar
  field `@encodedExample` and every request/response body `.example`.
- FR6: Every error variant described + example body (503 ready has no
  body).
- FR7: `UserDocsSuite`: `Docs.undocumented(endpoints passed to
  Docs.routes) == Nil` over all four served endpoints (POST /auth/signup,
  POST /auth/login, GET /health, GET /health/ready).
- FR8: `scripts/verify-rest-docs.sh` checks the served `/docs/docs.yaml`
  for summaries/descriptions/examples and the narrowed error statuses.

## Non-Functional
- Wire format unchanged (encode/decode identical); scalafmtCheck clean.
- Examples use obviously fake values only: `user@example.com`,
  `correct-horse-battery-staple`, and non-functional placeholder tokens
  (never a real credential or token). User ids are UUID-shaped but fake.
- No behaviour change.

## Acceptance Criteria
- Full suite green, zero `Docs.undocumented` gaps, verify script PASS,
  CI green.
- `/docs` shows summaries, descriptions, parameter/body examples and only
  the errors each endpoint can return.

## Out of Scope
- Behaviour changes; refresh/validate-token endpoints; AsyncAPI side
  (TD-19); adding typed 500 variants for unexpected failures (tapir's
  default empty 500 stays undeclared); client-side mirrors.

## Appendix: approved descriptions (2026-11-01)

Approved by the user exactly as proposed (2026-11-01); Phase 3 applies the
content below to code.

### Endpoints
| Endpoint | summary | description | tag |
|---|---|---|---|
| POST /auth/signup | Sign up | Creates an account and returns its first access token (201). The email is trimmed and lower-cased; an invalid email or a password under 8 characters → 400, an already-registered email → 409. Writes the account, token and `UserRegistered` outbox row in one transaction (event published asynchronously). Not idempotent — a retry with the same email → 409. | `auth` |
| POST /auth/login | Log in | Checks email + password and returns a fresh access token and refresh token (200). An invalid email or empty password → 400. An unknown email and a wrong password both → 401 with the same body; an unknown email still runs one password hash so timing doesn't reveal registered emails. Tokens are opaque; only their SHA-256 hashes are stored. | `auth` |
| GET /health | Liveness check | 200 while the process is up. Checks no dependencies. | `health` |
| GET /health/ready | Readiness check | 200 when Postgres answers a ping, 503 when it doesn't. Checks no other dependency. | `health` |

### Parameters
None: no served endpoint takes a path, query or header parameter (both
auth endpoints take a JSON request body; health takes nothing). Nothing
to describe or exemplify.

### Request/response DTOs

#### SignupRequest — "Credentials for a new account."
Body example: `{"email":"user@example.com","password":"correct-horse-battery-staple"}`

| Field | description | example |
|---|---|---|
| email | Email address; trimmed and lower-cased before storage. | `user@example.com` |
| password | Plaintext password, at least 8 characters. Never stored or logged. | `correct-horse-battery-staple` |

#### SignupResponse — "A new account and its first access token."
Body example: `{"userId":"5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e","token":"example-access-token-not-valid"}`

| Field | description | example |
|---|---|---|
| userId | New user's id (UUID). | `5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e` |
| token | Opaque access token (1-hour TTL). Shown once; store it client-side, never log it. | `example-access-token-not-valid` |

#### LoginRequest — "Credentials for an existing account."
Body example: `{"email":"user@example.com","password":"correct-horse-battery-staple"}`

| Field | description | example |
|---|---|---|
| email | Email address; trimmed and lower-cased before lookup. | `user@example.com` |
| password | Plaintext password; must be non-empty. Never stored or logged. | `correct-horse-battery-staple` |

#### LoginResponse — "A successful login and a fresh token pair."
Body example: `{"userId":"5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e","token":"example-access-token-not-valid","refreshToken":"example-refresh-token-not-valid"}`

| Field | description | example |
|---|---|---|
| userId | User's id (UUID). | `5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e` |
| token | Opaque access token (1-hour TTL). Shown once; never log it. | `example-access-token-not-valid` |
| refreshToken | Opaque refresh token (30-day TTL). Only its hash is stored; never log it. | `example-refresh-token-not-valid` |

#### ErrorResponse — "Error body."
Body examples are per error variant (below).

| Field | description | example |
|---|---|---|
| error | Human-readable reason. | `Enter a valid email address` |

### Error variants
| Status | Variant | When | Example body |
|---|---|---|---|
| 400 | InvalidEmail (signup) | `email` isn't shaped `local@host.tld` after trimming/lower-casing. Checked before the password. | `{"error":"Enter a valid email address"}` |
| 400 | PasswordTooShort (signup) | Password is shorter than 8 characters. | `{"error":"Password must be at least 8 characters"}` |
| 409 | DuplicateEmail (signup) | An account with that normalized email already exists; nothing is written. | `{"error":"An account with this email already exists"}` |
| 400 | InvalidEmail (login) | `email` isn't shaped `local@host.tld` after trimming/lower-casing. Checked before the password. | `{"error":"Enter a valid email address"}` |
| 400 | PasswordMissing (login) | Password is empty. | `{"error":"Enter your password"}` |
| 401 | InvalidCredentials (login) | Unknown email or wrong password — one generic response either way (a corrupt stored hash also returns this). | `{"error":"Email or password is incorrect"}` |
| 503 | — (health/ready) | Postgres didn't answer a ping. No body. | (no body) |

Response descriptions to put on each variant body: "Email is invalid.",
"Password is shorter than 8 characters.", "Email is already registered.",
"Email is invalid.", "Password is empty.", "Unknown email or wrong
password.", and on the 503 status code: "Postgres didn't answer a ping."
