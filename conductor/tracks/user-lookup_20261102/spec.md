# Specification: ADM-3 — `GET /users/{id}` customer lookup

## Overview

notification-service must resolve a `customerId` to an email address so the
Admin console's Customers tab can attribute every email and the send path can
address a real customer (ADR 0025). user-service owns the customerId → email
mapping but exposes no read endpoint. This track adds an unauthenticated
`GET /users/{id}`, the first read endpoint in this service.

## Functional Requirements

### 1. Store lookup
- `UserStore.findById(id: AccountId): F[Option[UserAccount]]`.
- Postgres: `SELECT id, email, created_at FROM "user_account" WHERE id = $uuid`,
  mapped to `UserAccount(id, email, createdAt)`.
- In-memory interpreter mirrors it (the in-memory `StoredAccount` must retain
  `createdAt`).
- Reading a stored email must not re-validate or throw: add a total
  `Email.fromStored(raw)` (documented as trusting the stored, normalized value).

### 2. Endpoint
- Tapir `GET /users/{id}`; `id` is a path `AccountId` with a codec/schema
  equivalent to order-service's `OrderId` — a malformed id is a decode failure
  (`400`), not a lookup miss.
- `200`: `{"userId": "string (UUID)", "email": "string", "createdAt": "ISO-8601 instant"}`.
- `400`: tapir path-decode failure (plain text) for a malformed id.
- `404`: `{"error": "User not found"}` for an unknown id.
- Unauthenticated (local/demo read API, same posture as the other services'
  reads; ADR 0025 records the caveat). Publishes nothing.
- Full tapir documentation: summary, description, tag; parameter description
  and example; every response field described; class-level example
  (TD-20 convention) so `UserDocsSuite` passes.
- Registered in `AppRoutes.all`'s `Docs.routes` and the served routes.

## Non-Functional Requirements
- Pure-FP/tagless-final style; no new dependencies.
- TDD; `sbt scalafmtCheck test` green; >80% coverage on new code.
- No auth/token changes; no event/registry changes.
- Update `conductor/product.md` (identity read endpoint) and README's curl
  examples.

## Acceptance Criteria
- A user created via `POST /auth/signup` can be fetched by its `userId` and
  returns the same normalized email.
- An unknown UUID returns `404`; a non-UUID returns `400`.
- `UserStoreSuite` and `UserStorePostgresSuite` cover found and unknown ids.
- `UserDocsSuite` reports every endpoint documented, and its exact-status list
  includes the new endpoint's `400`/`404`.
- `sbt scalafmtCheck test` passes.

## Out of Scope
- Authentication/authorization on the endpoint (future identity model).
- Listing/searching users, updating users, or email changes.
- The notification-service resolver and `sent_email` changes (separate ADM-3
  track in notification-service).
- gshop wiring (ADM-8).
