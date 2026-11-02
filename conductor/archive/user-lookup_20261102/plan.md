# Plan: ADM-3 — `GET /users/{id}` customer lookup

## Phase 1: Store lookup

- [x] Task: Failing `UserStoreSuite` tests: `findById` returns the created
  account (id, email, createdAt) and `None` for an unknown id (Red); add
  `findById` to the in-memory interpreter, keeping `createdAt` in
  `StoredAccount` (Green).
- [x] Task: Failing `UserStorePostgresSuite` test: create an account, then
  `findById` returns it with the persisted `createdAt`; unknown id → `None`
  (Red); implement the Postgres query and `Email.fromStored` (Green).
- [x] Task: Conductor - User Manual Verification 'Store lookup'
  (Protocol in workflow.md) — prompting off: verified by the committed test
  suites.

## Phase 2: Endpoint

- [x] Task: Failing `UserRoutesSuite`: `200` for a known id with
  `{userId,email,createdAt}`, `404` for an unknown UUID, `400` for a
  malformed id; failing `UserDocsSuite` status list entry (Red).
- [x] Task: Add `Codec`/`Schema` for `AccountId` (mirroring order-service's
  `OrderId`), `UserResponse`, the documented `getUserEndpoint` and
  `getUserServerEndpoint`, and register it in `AppRoutes` (Green).
- [x] Task: Update `UserDocsSuite` (endpoint set + expected statuses),
  `AppRoutesSuite` (signup → GET end-to-end), and the `findById` stub in
  `HealthRoutesSuite`'s anonymous `UserStore`.
- [x] Task: Conductor - User Manual Verification 'Endpoint'
  (Protocol in workflow.md) — prompting off: verified by the committed test
  suites.

## Phase 3: Wrap-up

- [x] Task: Update `conductor/product.md` and README (curl example), then run
  `sbt scalafmtCheck test` and confirm green.
- [x] Task: Conductor - User Manual Verification 'Wrap-up'
  (Protocol in workflow.md) — prompting off: full suite green (84 passed).

## Review

- Reviewed 2026-11-02 (conductor:review): plan compliance yes; style pass —
  parse-don't-validate `AccountId` codec mirroring `OrderId`, total
  `Email.fromStored` for DB reads, narrow `Monad` bounds. New tests cover
  known/unknown/malformed ids, the full route through `AppRoutes`, and the
  docs-coverage status list; full suite 84 passed. One docs correction
  applied: `400` is a plain-text tapir path-decode failure, not a JSON body.
