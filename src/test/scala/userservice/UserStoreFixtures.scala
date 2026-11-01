package userservice

import java.time.Instant

object UserStoreFixtures {
  val envelope: EventEnvelope = EventEnvelope(
    eventId = "event-1",
    eventType = "UserRegistered",
    timestamp = Instant.parse("2026-10-28T12:00:00Z"),
    source = "user-service",
    correlationId = "user-1",
    payload = io.circe.Json.obj()
  )
}
