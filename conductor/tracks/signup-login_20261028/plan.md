# Plan: US-16.1–16.3 — signup, login, UserRegistered

## Phase 1: Auth foundations + schema

- [ ] Task: Write failing tests for `PasswordHasher` (PBKDF2 format `pbkdf2$iter$salt$hash`, verify true/false, random salt per hash) and `TokenIssuer` (random opaque tokens, SHA-256 hashing, configured 1h/30d expiries) (Red)
- [ ] Task: Implement `PasswordHasher`, `TokenIssuer`, and auth config (`access-token-ttl = 1h`, `refresh-token-ttl = 30d`, `pbkdf2-iterations`) (Green)
- [ ] Task: Replace scaffold migration `V1` with `user_account` + `auth_tokens` (unique normalized email, unique token hash, FK); keep `V2` outbox; update `MigrationsSuite` (Green)
- [ ] Task: Refactor, `sbt scalafmtCheck test` green
- [ ] Task: Conductor - User Manual Verification 'Auth foundations + schema' (Protocol in workflow.md)

## Phase 2: Store

- [ ] Task: Write failing store tests (in-memory + Postgres): create account returns the user and writes its access token + `UserRegistered` outbox row in one transaction; duplicate email reports a conflict with no extra rows; `findByEmail` finds the normalized email; login records access + refresh tokens for the user (Red)
- [ ] Task: Rework `UserStore` (replace the generated CRUD methods) with `createAccount`, `findByEmail`, `recordLoginTokens`, `ping` in both interpreters (Green)
- [ ] Task: Refactor, tests green
- [ ] Task: Conductor - User Manual Verification 'Store' (Protocol in workflow.md)

## Phase 3: Auth routes

- [ ] Task: Write failing `AuthRoutesSuite`: signup 201 `{userId, token}` + outbox envelope (`eventType=UserRegistered`, `correlationId=userId`, payload `{userId,email}`); invalid email/short password 400; duplicate 409; login 200 `{token, refreshToken}`; unknown email and wrong password both 401 with the same message; no password/token values appear in logs (Red)
- [ ] Task: Implement `AuthRoutes` (error ADT → 400/401/409) and wire it in `AppRoutes`/`Main`; delete `UserRoutes` and the generic CRUD surface (Green)
- [ ] Task: Register `UserRegistered` as `Publish(Cdc)` on `user-events` in `AsyncApiChannels`, remove the example channels (Green)
- [ ] Task: Refactor, full suite green
- [ ] Task: Conductor - User Manual Verification 'Auth routes' (Protocol in workflow.md)

## Phase 4: Infra, contracts, live proof

- [ ] Task: Add `user-outbox-connector.json` to `gluon/infra/k8s/local-infra/debezium-connectors-register.yaml`; add `environments/local/user-service.values.yaml` and include the service in `bin/k8s-local-up`
- [ ] Task: Update `gluon/docs/system-design.md` (signup/login 400/401/409 shapes, user-service status/events), README, product.md and tech-stack
- [ ] Task: `schemaRegistryCheck` against Apicurio; add `scripts/verify-auth.sh` (signup/duplicate/401/login, one user + tokens + outbox row in Postgres, one `user-events: UserRegistered` message on Kafka)
- [ ] Task: Rebuild/redeploy user-service, run the live script
- [ ] Task: Conductor - User Manual Verification 'Infra, contracts, live proof' (Protocol in workflow.md)

## Risks / notes

- `user_account` is used instead of the reserved word `user`; the scaffold's placeholder `V1` has never been deployed, so replacing it is safe.
- Token values are returned once and never logged; only SHA-256 hashes are stored (same rationale as the gshop mock, but PBKDF2 for passwords here).
- No refresh endpoint: refresh tokens are stored/returned as the contract requires, but cannot be exchanged yet (out of scope).
- Debezium needs `wal_level=logical` on the user Postgres; copy the per-service deployment pattern from an existing service.
- Live deploy adds a seventh service to the cluster; the demo frontends are unaffected.
