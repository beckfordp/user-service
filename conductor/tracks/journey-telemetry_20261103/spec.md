# Spec: Journey telemetry (gluon PERF-4.1, 4.3, 4.8)

## Overview
gluon PLAN.md Performance 4 for user-service: adopt purerestlib 0.8.1 cross-cutting telemetry and
add the service's own business counters for the Christmas Rush dashboard.

## Functional Requirements
1. Bump purerestlib to 0.8.1; `Main` passes `runtime.metrics` (PERF-4.1).
2. `PoolMetrics` on the pool `user` (PERF-4.3).
3. `UserJourneyMetrics` store decorator: `users.registrations{outcome=created|duplicate}`,
   `users.logins` (successful logins, counted at token issue) (PERF-4.8).

## Non-Functional Requirements
- No ids on metric labels. Coverage > 80% on new code. scalafmt clean.

## Acceptance Criteria
- Unit tests for the new metrics. Live: metrics port shows the new series after traffic.

## Out of Scope
- Spans / OTLP (Perf 5).
