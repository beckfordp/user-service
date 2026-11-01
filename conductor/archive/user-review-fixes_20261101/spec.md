# Spec: user-service review fixes (gluon TD-16.7)

## Overview
Full review of user-service main + test code (2026-11-01) against
`code_styleguides/scala-fp.md` (Review checklist) and `general.md`, with extra
attention to auth security (hashing, timing-safe comparison, token handling,
secret logging). Baseline: 52 tests green at `d5f7614`. No REST/Kafka wire
change.

## Findings

### High (checklist "no")
- **H1 Ids/time outside F.** `TokenId.fromUuid(UUID.randomUUID())` evaluated
  unsuspended in signup/login (3 sites); in-memory `createAccount` calls
  `Instant.now()` inside `Ref.modify` (impure, may re-run).
- **H2 Over-broad capabilities.** Auth server endpoints, `AuthEvents` and
  `UserStore.inMemory` require `Sync` only to call `delay`/randomness;
  `PasswordHasher`/`TokenIssuer` are not algebras, so every caller needs `Sync`.
- **H3 Raw input not parsed to domain types.** Email stays a `String` through
  store/events; `NewToken.kind` is a `String` re-derived from `TokenKind.wire`.
- **H4 Business rules in the shell, untested as values.** Signup/login
  validation lives in `AuthRoutes` (not `domain/`), errors are a stringly
  `Invalid(String)`; no value-level/property tests.

### Medium
- **M1 Login timing oracle.** Unknown email returns before any PBKDF2 run, a
  known one runs 210k iterations — response time reveals registered emails.
- **M2 `verify` collapses outcomes.** A malformed stored hash reads as "wrong
  password" (`Boolean`), silently; uses `Try` (catches all non-fatal).
- **M3 No guard that secrets stay out of logs.** Nothing asserts passwords /
  raw tokens never reach a log line or its context.
- **M4 Randomness at construction.** `SecureRandom` created in case-class `val`s
  (§7: never at initialisation).

### Low
- **L1** `toLowerCase` is locale-sensitive (Turkish dotless i) — use `Locale.ROOT`.
- **L2** `auth.iterations` unvalidated: `0`/negative makes every signup 500.
- **L3** `AppRoutes` builds `PasswordHasher`/`TokenIssuer` twice.
- **L4** `TokenIssuer` uses `Instant.now()` instead of `Clock`.
- **L5** Missing tests: login blank password 400; stored hash is never the
  plaintext.
- **L6** (skip) CORS allows PATCH/PUT/DELETE + exposes `X-Total-Count`, unused
  here — platform template default, kept for cross-repo consistency.
- **L7** (defer) No password max length / login rate limit (PBKDF2 CPU-DoS
  surface) — behaviour change, belongs with a gateway/rate-limit decision.

Checked and fine: PBKDF2-HMAC-SHA256 with 16-byte random salt, iterations
stored with the hash; constant-time `MessageDigest.isEqual`; tokens 32 bytes
`SecureRandom`, only SHA-256 stored; generic 401 for unknown email vs wrong
password; no token-validation endpoint exists yet (nothing to validate);
signup/outbox atomic in one transaction; SQL parameterized.

## Functional Requirements
- FR-1 (H3, H4, L1): `domain/` gains `Email` (opaque, smart constructor:
  trim + `Locale.ROOT` lower-case + pattern), `TokenKind`, and
  `Credentials` (`parseSignup`/`parseLogin` → `Either[CredentialsError, …]`,
  ADT `InvalidEmail | PasswordTooShort | PasswordMissing`, messages mapped at
  the edge, unchanged). Store/events take `Email`; `NewToken.kind: TokenKind`.
- FR-2 (H1, H2, M4, L3, L4): `PasswordHasher[F]` / `TokenIssuer[F]` algebras
  built inside `F`; ids via `UUIDGen`, time via `Clock`; routes narrowed to
  `Monad`+`UUIDGen`, `AuthEvents` to `Monad`+`Clock`+`UUIDGen`, in-memory
  store to `Concurrent`+`Clock`; hasher/issuer built once in `Main`.
- FR-3 (M1, M2, M3, L2, L5): unknown-email login verifies against a dummy
  hash; `verify` returns `PasswordCheck` (`Match | Mismatch | Malformed`),
  malformed logged at error (same 401); test that no log line/context holds
  a password or raw token; `iterations >= 1` validated at startup; L5 tests.

## Acceptance Criteria
- Each fixed finding has a failing-first test now green; full suite green,
  coverage >80%, scalafmtCheck clean, CI green; wire format unchanged.

## Out of Scope
- L6, L7; refresh/validate-token endpoints; REST docs (TD-20.10).
