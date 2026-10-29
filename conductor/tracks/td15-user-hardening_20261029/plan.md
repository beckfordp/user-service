# TD-15.7 Implementation Plan

## Phase 1 — Typed ids
- [x] Add `AccountId`/`TokenId`; update models, store, AuthRoutes; remove `UUID.fromString`; update tests. — a4b7766
- [x] Task: Conductor - User Manual Verification 'Phase 1 — Typed ids' (Protocol in workflow.md) — covered by tests

## Phase 2 — Config, ScalaCheck, narrowing
- [x] Add `asyncApiHtmlDir`; `munit-scalacheck` properties; narrow bounds. — a4b7766
- [x] Task: Conductor - User Manual Verification 'Phase 2 — Purity' (Protocol in workflow.md) — covered by tests

## Phase 3 — Verification
- [x] Full suite + coverage + scalafmt. — 51 tests, 90.8%/86.0% coverage
- [x] Task: Conductor - User Manual Verification 'Phase 3 — Verification' (Protocol in workflow.md) — covered by the suite
