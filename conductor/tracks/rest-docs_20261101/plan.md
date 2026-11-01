# Plan — user-service: REST docs convention (gluon TD-20.10)

## Phase 1: purerestlib 0.7.3
- [x] Task: bump `purerestlibVersion` 0.4.0 → 0.7.3 (+ aligned pins); fix API drift until suite green; sync tech-stack.md — other pins already aligned, no drift, 76/76 a1838c2
- [x] Task: Conductor - User Manual Verification 'Phase 1: purerestlib 0.7.3' (Protocol in workflow.md) — covered by suite

## Phase 2: Error outputs and docs coverage
- [x] Task: pin the already-narrowed per-endpoint error outputs (TD-16.7) — signup 400×2 + 409, login 400×2 + 401, health 503 — with a `UserDocsSuite` status walk — `List(400,400,409)`, `List(400,400,401)`, `Nil`, `List(503)` 45581ee
- [x] Task: coverage test first — `UserDocsSuite` asserts `Docs.undocumented(endpoints passed to Docs.routes) == Nil`; red: 28 gaps (8 missing summary/description, 5 missing response descriptions, 10 missing field descriptions, 5 missing examples) → 0; Appendix approved 2026-11-01 45581ee
- [x] Task: Conductor - User Manual Verification 'Phase 2: Error outputs and docs coverage' (Protocol in workflow.md) — covered by `UserDocsSuite` (2 tests)

## Phase 3: Descriptions and examples (Appendix approved 2026-11-01)
- [x] Task: apply the Appendix's summaries/descriptions/tags, DTO annotations + examples and error descriptions + examples; coverage test green — 13c8fa6
- [x] Task: Conductor - User Manual Verification 'Phase 3: Descriptions and examples' (Protocol in workflow.md) — covered by Phase 4's verify script

## Phase 4: Smoke test
- [x] Task: `scripts/verify-rest-docs.sh` — run service, fetch `/docs/docs.yaml`, assert summaries/descriptions/examples + narrowed errors; scalafmtCheck; CI green — PASS 43 checks; fake-auth-example checks; full suite 78/78 d419025
- [x] Task: Conductor - User Manual Verification 'Phase 4: Smoke test' (Protocol in workflow.md) — `scripts/verify-rest-docs.sh` PASS

## Phase: Review Fixes
- [x] Task: Apply review suggestions — verify script now waits 180s for readiness and warns when GitHub Packages credentials are unset; rerun PASS 43 checks d0ac403
