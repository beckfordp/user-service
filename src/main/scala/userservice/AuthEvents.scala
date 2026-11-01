package userservice

import cats.effect.Sync
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.{description, encodedExample}
import userservice.domain.{AccountId, Email}

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

@description("New user signed up: POST /auth/signup succeeded.")
final case class UserRegisteredEvent(
    @description("User UUID. Also the partition key.")
    @encodedExample("5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e")
    userId: String,
    @description("User's email address.")
    @encodedExample("jane@example.com")
    email: String
)

object UserRegisteredEvent {
  implicit val codec: Codec[UserRegisteredEvent] = deriveCodec
  implicit val schema: Schema[UserRegisteredEvent] = Schema.derived
}

object AuthEvents {

  private val eventSource = "user-service"

  /** The `UserRegistered` outbox envelope (US-16.3), correlated by userId. */
  def userRegistered[F[_]: Sync](
      userId: AccountId,
      email: Email
  ): F[EventEnvelope] =
    for {
      eventId <- Sync[F].delay(UUID.randomUUID().toString)
      timestamp <- Sync[F].realTimeInstant
    } yield EventEnvelope(
      eventId = eventId,
      eventType = "UserRegistered",
      timestamp = timestamp,
      source = eventSource,
      correlationId = AccountId.render(userId),
      payload = UserRegisteredEvent(
        AccountId.render(userId),
        Email.render(email)
      ).asJson
    )
}
