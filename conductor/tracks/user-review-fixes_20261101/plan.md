# Plan — user-service review fixes (gluon TD-16.7)

## Phase 1: Domain parsing (FR-1) [checkpoint: 680a9cd]
- [x] Task: tests first — `CredentialsSuite` (values + ScalaCheck: normalization idempotent, short passwords rejected, Locale.ROOT) and typed `TokenKind`/`Email` through store fixtures (red) → `domain/Email`, `domain/Credentials`, `domain/TokenKind`; store/events/routes take typed values; green — red compile failure → 64/64; signup errors 400/400/409, login 400/400/401 8f1de17
- [x] Task: Conductor - User Manual Verification 'Phase 1: Domain parsing' (Protocol in workflow.md) — covered by suite (64 green)

## Phase 2: Effects and capabilities (FR-2) [checkpoint: be9a506]
- [x] Task: tests first — hasher/issuer built in `F`, issuer expiry from `Clock` (`TestControl`), in-memory `createdAt` from `Clock` (red) → `PasswordHasher[F]`/`TokenIssuer[F]` algebras, `UUIDGen`/`Clock`, narrowed bounds, one hasher/issuer wired in `Main`; green — red compile failure → 66/66; signup also needs `Clock` (event timestamp); in-memory store `Monad`+`Clock`+`Ref.Make`; `cats-effect-testkit` added (Test) 6374004
- [x] Task: Conductor - User Manual Verification 'Phase 2: Effects and capabilities' (Protocol in workflow.md) — covered by suite (66 green)

## Phase 3: Auth hardening (FR-3) [checkpoint: ddf003a]
- [x] Task: tests first — unknown-email login runs one verify; malformed hash → `Malformed` + error log + 401; no secret in any log; `iterations = 0` rejected; blank-password 400; stored hash ≠ plaintext (red) → implement; green — red compile failure → 76/76; dummy check uses random salt+hash (no startup PBKDF2); TTLs > 0 also validated 438fc2a
- [x] Task: Conductor - User Manual Verification 'Phase 3: Auth hardening' (Protocol in workflow.md) — covered by suite (76 green; 93.07% stmt / 92.59% branch)

## Phase: Review Fixes
- [x] Task: Apply review suggestions — tech-stack.md synced to the pinned purerestlib 0.4.0 / purekafka 0.6.1 (was 0.1.0 / 0.5.0); no code findings 24bb4f1
- [x] Task: Final verification — `sbt scalafmtCheckAll` clean; full suite 76/76; `coverageReport` 93.07% stmt / 92.59% branch (>80% required)
