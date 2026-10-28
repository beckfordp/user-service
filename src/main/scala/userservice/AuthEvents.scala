package userservice

import cats.effect.Sync
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import sttp.tapir.Schema

import java.time.Instant
import java.util.UUID

/** Shared Kafka envelope (ADR 0009) — same shape as every other service. */
final case class EventEnvelope(
    eventId: String,
    eventType: String,
    timestamp: Instant,
    source: String,
    correlationId: String,
    payload: io.circe.Json
)

object EventEnvelope {
  implicit val codec: Codec[EventEnvelope] = deriveCodec
}

final case class UserRegisteredEvent(userId: String, email: String)

object UserRegisteredEvent {
  implicit val codec: Codec[UserRegisteredEvent] = deriveCodec
  implicit val schema: Schema[UserRegisteredEvent] = Schema.derived
}

object AuthEvents {

  private val eventSource = "user-service"

  /** The `UserRegistered` outbox envelope (US-16.3), correlated by userId. */
  def userRegistered[F[_]: Sync](
      userId: String,
      email: String
  ): F[EventEnvelope] =
    for {
      eventId <- Sync[F].delay(UUID.randomUUID().toString)
      timestamp <- Sync[F].realTimeInstant
    } yield EventEnvelope(
      eventId = eventId,
      eventType = "UserRegistered",
      timestamp = timestamp,
      source = eventSource,
      correlationId = userId,
      payload = UserRegisteredEvent(userId, email).asJson
    )
}
