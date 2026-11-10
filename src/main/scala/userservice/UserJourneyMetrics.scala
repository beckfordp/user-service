// SPDX-License-Identifier: MIT

package userservice

import cats.Monad
import cats.syntax.all._
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import userservice.domain.AccountId

/** Account telemetry (gluon PERF-4.8), as a `UserStore` decorator:
  *
  *   - `users.registrations{outcome=created|duplicate}` per `createAccount`
  *   - `users.logins`: successful logins, counted when their tokens are
  *     recorded (a failed password check never reaches the store)
  */
object UserJourneyMetrics {

  def outcome(error: CreateAccountError): String = error match {
    case DuplicateEmail => "duplicate"
  }

  def instrument[F[_]: Monad](
      store: UserStore[F],
      meter: Meter[F]
  ): F[UserStore[F]] =
    (
      meter.counter[Long]("users.registrations").create,
      meter.counter[Long]("users.logins").create
    ).mapN { (registrations, logins) =>
      new UserStore[F] {
        export store.{createAccount => _, recordLoginTokens => _, *}

        def createAccount(
            id: AccountId,
            email: domain.Email,
            passwordHash: String,
            accessToken: NewToken,
            outbox: EventEnvelope
        ): F[Either[CreateAccountError, UserAccount]] =
          store
            .createAccount(id, email, passwordHash, accessToken, outbox)
            .flatTap(result =>
              registrations.inc(
                Attribute("outcome", result.fold(outcome, _ => "created"))
              )
            )

        def recordLoginTokens(
            accessToken: NewToken,
            refreshToken: NewToken
        ): F[Unit] =
          store.recordLoginTokens(accessToken, refreshToken) *> logins.inc()
      }
    }
}
