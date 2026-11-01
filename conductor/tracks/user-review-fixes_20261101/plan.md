# Plan — user-service review fixes (gluon TD-16.7)

## Phase 1: Domain parsing (FR-1) [checkpoint: 680a9cd]
- [x] Task: tests first — `CredentialsSuite` (values + ScalaCheck: normalization idempotent, short passwords rejected, Locale.ROOT) and typed `TokenKind`/`Email` through store fixtures (red) → `domain/Email`, `domain/Credentials`, `domain/TokenKind`; store/events/routes take typed values; green — red compile failure → 64/64; signup errors 400/400/409, login 400/400/401 8f1de17
- [x] Task: Conductor - User Manual Verification 'Phase 1: Domain parsing' (Protocol in workflow.md) — covered by suite (64 green)

## Phase 2: Effects and capabilities (FR-2)
- [ ] Task: tests first — hasher/issuer built in `F`, issuer expiry from `Clock` (`TestControl`), in-memory `createdAt` from `Clock` (red) → `PasswordHasher[F]`/`TokenIssuer[F]` algebras, `UUIDGen`/`Clock`, narrowed bounds, one hasher/issuer wired in `Main`; green
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Effects and capabilities' (Protocol in workflow.md)

## Phase 3: Auth hardening (FR-3)
- [ ] Task: tests first — unknown-email login runs one verify; malformed hash → `Malformed` + error log + 401; no secret in any log; `iterations = 0` rejected; blank-password 400; stored hash ≠ plaintext (red) → implement; green
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Auth hardening' (Protocol in workflow.md)
