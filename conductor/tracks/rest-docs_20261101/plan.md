# Plan — user-service: REST docs convention (gluon TD-20.10)

## Phase 1: purerestlib 0.7.3
- [x] Task: bump `purerestlibVersion` 0.4.0 → 0.7.3 (+ aligned pins); fix API drift until suite green; sync tech-stack.md — other pins already aligned, no drift, 76/76 a1838c2
- [x] Task: Conductor - User Manual Verification 'Phase 1: purerestlib 0.7.3' (Protocol in workflow.md) — covered by suite

## Phase 2: Error outputs and docs coverage
- [ ] Task: pin the already-narrowed per-endpoint error outputs (TD-16.7) — signup 400×2 + 409, login 400×2 + 401, health 503 — with a `UserDocsSuite` status walk
- [~] Task: coverage test first — `UserDocsSuite` asserts `Docs.undocumented(endpoints passed to Docs.routes) == Nil`; red: 28 gaps (8 missing summary/description, 5 missing response descriptions, 10 missing field descriptions, 5 missing examples); test left uncommitted pending Appendix approval
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Error outputs and docs coverage' (Protocol in workflow.md)

## Phase 3: Descriptions and examples (blocked on approval of the Appendix)
- [ ] Task: after user approval, apply the Appendix's summaries/descriptions/tags, DTO annotations + examples and error descriptions + examples; coverage test green
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Descriptions and examples' (Protocol in workflow.md)

## Phase 4: Smoke test
- [ ] Task: `scripts/verify-rest-docs.sh` — run service, fetch `/docs/docs.yaml`, assert summaries/descriptions/examples + narrowed errors; scalafmtCheck; CI green
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Smoke test' (Protocol in workflow.md)
