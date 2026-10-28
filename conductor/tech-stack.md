# Tech Stack — user-service

## Language / runtime
- Scala 3.9.0
- JDK 21

## Effects / HTTP
- Cats Effect 3.7.0
- http4s 0.23.37 (ember-server)
- circe 0.14.16
- CORS — `org.http4s.server.middleware.CORS` wraps every response
  (`Cors.scala`), generated from the platform template (TD-1).

## API layer
- tapir 1.11.25 — route definitions and generated Swagger/OpenAPI docs
  (`purerest.docs.Docs`) at `/docs`
- purekafka.docs (`AsyncApiChannel`/`Docs`) — AsyncAPI docs for this
  service's Kafka contracts; `schemaRegistryCheck` (sbt-purekafka-docs)
  registers/validates schemas against Apicurio at build/CI time (ADR 0003).

## Persistence
- skunk 1.0.0 — pure-FP, non-blocking Postgres access
- skunk-circe — `Codec[Json]` for the outbox payload column
- Flyway 11.8.2 — migrations on startup
- postgresql JDBC 42.7.13 — Flyway-only
- One Postgres database (`user`); no reads/writes of another service's DB.

## Auth
- PBKDF2-HMAC-SHA256 (`PasswordHasher`, JDK only) with a per-user random salt
  and configurable iterations (default 210k). Stored form
  `pbkdf2$iters$salt$hash`.
- Opaque random tokens (`TokenIssuer`, 32 bytes base64url) — raw value
  returned once, only its SHA-256 hash stored in `auth_tokens`. Access TTL 1h,
  refresh 30d (`auth` config block). No refresh endpoint yet.

## Messaging
- fs2-kafka 3.6.0 and purekafka 0.3.0 — used for the transactional-outbox
  side of `UserRegistered` (ADR 0008/0009/0010); no direct producer calls.
- Debezium connector for this service's `outbox_events` added to gluon's
  `infra/k8s/local-infra` when the event lands.

## Testing
- munit 1.3.6 + munit-cats-effect 2.2.1
- log4cats-testing 2.8.0 — structured-log assertions
- testcontainers-scala 0.43.6 (postgresql + munit modules)
- scalafmt (default Scala 3 style) — `sbt scalafmtCheck test` in CI

## Shared platform library
- `purerestlib` 0.1.0 (`io.github.beckfordp`) — tracing, structured logging,
  metrics, resilience, tapir docs. Published artifact from GitHub Packages.

## Packaging / local deploy
- sbt-native-packager (`JavaAppPackaging`, `DockerPlugin`), deployed to the
  local OrbStack k8s cluster via gluon's generic Helm chart (ADR 0007).
