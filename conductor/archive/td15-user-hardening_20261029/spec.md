# TD-15.7 — Cross-repo consistency hardening (user-service)

## Overview

User-service slice of gluon TD-15: typed account/token ids, `asyncApiHtmlDir`
config, ScalaCheck, narrower effect bounds.

## Functional Requirements

1. **Typed ids** — opaque `AccountId`/`TokenId`, minted typed and passed through
   stores; remove raw `UUID.fromString`.
2. **Config** — `asyncApiHtmlDir` with `${?ASYNCAPI_HTML_DIR}`; no `sys.env`.
3. **Tests** — `munit-scalacheck` + id properties.
4. **Effect constraints** — `Cors`/`HealthRoutes`/route handlers narrowed where
   possible.

## Acceptance Criteria

- No raw UUID parsing in stores; full suite + scalafmt green.
