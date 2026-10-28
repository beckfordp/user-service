# Specification: US-16.1–16.3 — signup, login, UserRegistered

## Overview

`user-service` was scaffolded with a generic placeholder `/users` CRUD
surface. This track replaces it with the real identity API from
`gluon/docs/system-design.md`: `POST /auth/signup` and `POST /auth/login`,
opaque database-backed tokens, PBKDF2 password hashing, and a
`user-events: UserRegistered` event published through the transactional
outbox when a signup succeeds.

## Functional Requirements

- **FR-1 — Auth API only.** The generated `/users` CRUD endpoints and generic
  `User` store are removed; the service exposes `/auth/signup`,
  `/auth/login`, health and docs.
- **FR-2 — Schema.** Flyway creates `user_account` (`id`, unique normalized
  `email`, `password_hash`, `created_at`), `auth_tokens` (`id`, `user_id` FK,
  unique `token_hash`, `kind` = `access`/`refresh`, `expires_at`,
  `created_at`), and keeps the `outbox_events` table.
- **FR-3 — Password hashing.** PBKDF2-HMAC-SHA256 with a per-user random salt
  and configurable iterations; stored as
  `pbkdf2$<iterations>$<salt>$<hash>`. Never logged or returned.
- **FR-4 — Tokens.** Cryptographically random opaque tokens (raw value
  returned once, only its SHA-256 hash stored). Access TTL 1 hour, refresh
  TTL 30 days; lifetimes configurable. No refresh endpoint.
- **FR-5 — Signup.** `POST /auth/signup {email,password}`: normalize email,
  validate format and password ≥ 8; `201 {userId, token}`; duplicate email →
  `409`; invalid input → `400`. On success, write `UserRegistered` (envelope,
  `correlationId = userId`, payload `{userId,email}`) to the outbox in the
  same transaction.
- **FR-6 — Login.** `POST /auth/login {email,password}`: `200 {token,
  refreshToken}`; unknown email and wrong password both return the same `401`
  message; no event.
- **FR-7 — Contracts.** `gluon/docs/system-design.md` gets the exact
  `400`/`401`/`409` shapes for both endpoints; AsyncAPI registers
  `UserRegistered` as `Publish(Cdc)` on `user-events`; `schemaRegistryCheck`
  passes; a Debezium connector for user-service is added to
  `infra/k8s/local-infra`.
- **FR-8 — Live proof.** Deployed to local k8s: signup creates one user,
  returns a token, writes one outbox row and produces one
  `user-events: UserRegistered` message; login returns access + refresh;
  wrong password is 401; duplicate signup is 409.

## Non-Functional Requirements

- No new runtime dependencies (PBKDF2/tokens use the JDK + existing libs).
- Passwords/tokens never logged; generic login errors; no secrets committed.
- `sbt scalafmtCheck test` green; Postgres integration tests via
  Testcontainers.

## Acceptance Criteria

- Both endpoints match the documented contracts and tests cover
  success/validation/duplicate/401 paths and the outbox event.
- Live cluster check passes; `system-design.md`, README/product/tech-stack
  updated.
- gshop's mock auth can later be swapped for these contracts without changing
  the UI shape.

## Out of Scope

- Refresh/logout/revocation endpoints, email verification, password reset,
  roles.
- notification-service's consumer (US-16.4) and gshop wiring (US-16.5) —
  separate repos/tracks.
- Admin identity (`userId` on orders) — separate follow-up.
