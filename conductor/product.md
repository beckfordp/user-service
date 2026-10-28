# Product Guide — user-service

## Context
Full cross-repo vision lives in the `gluon` platform repo, not here:
[`../../../docs/product.md`](../../../docs/product.md),
[`../../../docs/system-design.md`](../../../docs/system-design.md),
[`../../../docs/user-stories.md`](../../../docs/user-stories.md),
[`../../../PLAN.md`](../../../PLAN.md) (Checkout Journey 2).

## What this service does
The platform's identity service: customers sign up and log in before
checkout, and a successful signup publishes `user-events: UserRegistered`
for notification-service's welcome email. It owns only account identity —
never orders, carts, or payment data.

The repo was scaffolded from `pure-service-generator` with a generic
`user` CRUD placeholder. The US-16 track replaces that with the auth domain;
the generated CRUD surface is not an intended public API.

## Domain model
- **User account** — `id`, unique normalized `email`, password hash (never
  the plaintext), `createdAt`.
- **Auth token** — issued on signup/login; `login` also returns a
  `refreshToken`. Opaque random tokens (32 bytes), stored only as SHA-256
  hashes in `auth_tokens`; access TTL 1 hour, refresh TTL 30 days. Passwords
  are PBKDF2-HMAC-SHA256 hashes (per-user salt, 210k iterations by default),
  never stored or logged in plaintext.
- `POST /auth/signup` and `POST /auth/login` are the only public auth
  contracts for now (see `gluon/docs/system-design.md`).

## User stories in scope
From `../../../docs/user-stories.md`:
- **US-16.1** — `POST /auth/signup` — create user, issue token
- **US-16.2** — `POST /auth/login` — authenticate, issue token + refreshToken
- **US-16.3** — publish `user-events: UserRegistered` on signup

## Sequencing (`../../../PLAN.md`)
Checkout Journey 2, independent of Journeys 1/3/4/5. gshop's signup/login UI
is already built as a mock-backed prototype and is swapped onto this
service's real contracts in the same journey.

## Events
- Publishes: `user-events` (`UserRegistered`) — wrapped in the shared
  envelope (ADR 0009), written through a transactional outbox + Debezium CDC
  (ADR 0008), like every other publishing service.
- Consumes: none.

## Out of scope
- Profiles, saved addresses, password reset/change, email verification,
  roles/authorization, logout/revocation endpoints (token refresh endpoint
  is not yet specified — US-16 only requires issuing a refreshToken).
- Order/cart/payment data (other services' jobs).
