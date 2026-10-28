# Product Guidelines — user-service

## Code & engineering conventions
Canonical source: [`development-guidelines.md`](../development-guidelines.md)
(pure-FP, tagless-final, ADT error modeling, no partial functions, no
null/var, explicit DI, exhaustive pattern matching). This file doesn't
restate those — keep following them as the service grows.

## API design conventions
- Plain resource JSON responses, standard HTTP status codes — no response
  envelope.
- Errors: small JSON error object, status mapped 1:1 from the domain error
  ADT — one explicit `errorOut` mapping per case.
- Auth paths are `/auth/signup` and `/auth/login` (per
  `gluon/docs/system-design.md`), not the generated `/users` CRUD paths.
- One dedicated Postgres database per service (`user`) — never read/write
  another service's database directly.
- Swagger/OpenAPI docs generated from the same tapir endpoint definitions as
  the real routes (`purerest.docs.Docs`) — stays in sync by construction.

## Security conventions
- Passwords are never stored, logged, or echoed; only a slow hash is kept.
- Login failures use one generic message whether the email is unknown or the
  password is wrong.
- Emails are normalized (trimmed, lower-cased) before storage/lookup.
- Tokens are opaque to clients; never log token values.
- No auth secret is committed — configuration only, overridable per
  environment.

## Testing
- scalafmt + munit + munit-cats-effect; Testcontainers-backed Postgres for
  integration tests, no manual local Postgres needed.
- `sbt scalafmtCheck test` before every commit (CI enforces the same).
