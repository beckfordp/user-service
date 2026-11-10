// SPDX-License-Identifier: MIT

package userservice

import cats.effect.Sync
import cats.syntax.all._
import userservice.domain.TokenKind

import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.Base64
import scala.concurrent.duration.FiniteDuration

/** An issued token: the raw value is returned to the caller once; only `hash`
  * is persisted.
  */
final case class IssuedToken(
    value: String,
    hash: String,
    kind: TokenKind,
    expiresAt: Instant
)

/** Token-issuing algebra (US-16). */
trait TokenIssuer[F[_]] {
  def issue(kind: TokenKind): F[IssuedToken]
}

object TokenIssuer {

  private val tokenBytes = 32

  /** Opaque random tokens, 32 bytes of `SecureRandom` base64url-encoded, expiry
    * from `Clock[F]`. Storage keeps only the SHA-256 hash, so a database leak
    * doesn't expose usable tokens. The `SecureRandom` is created inside `F`.
    */
  def secureRandom[F[_]: Sync](
      accessTtl: FiniteDuration,
      refreshTtl: FiniteDuration
  ): F[TokenIssuer[F]] =
    Sync[F].delay(new SecureRandom()).map { random =>
      val encoder = Base64.getUrlEncoder.withoutPadding()

      new TokenIssuer[F] {
        def issue(kind: TokenKind): F[IssuedToken] =
          for {
            value <- Sync[F].delay {
              val bytes = new Array[Byte](tokenBytes)
              random.nextBytes(bytes)
              encoder.encodeToString(bytes)
            }
            now <- Sync[F].realTimeInstant
          } yield {
            val ttl = kind match {
              case TokenKind.Access  => accessTtl
              case TokenKind.Refresh => refreshTtl
            }
            IssuedToken(
              value = value,
              hash = hash(value),
              kind = kind,
              expiresAt = now.plusSeconds(ttl.toSeconds)
            )
          }
      }
    }

  /** Hex SHA-256 of a raw token value — the only form ever stored. */
  def hash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes("UTF-8"))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
}
