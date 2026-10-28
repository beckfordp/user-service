package userservice

import cats.effect.Sync

import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.Base64
import scala.concurrent.duration.FiniteDuration

sealed trait TokenKind {
  def wire: String
}

object TokenKind {
  case object Access extends TokenKind { val wire = "access" }
  case object Refresh extends TokenKind { val wire = "refresh" }
}

/** An issued token: the raw value is returned to the caller once; only `hash`
  * is persisted.
  */
final case class IssuedToken(
    value: String,
    hash: String,
    kind: TokenKind,
    expiresAt: Instant
)

/** Opaque random tokens (US-16), 32 bytes of `SecureRandom` base64url-encoded.
  * Storage keeps only the SHA-256 hash, so a database leak doesn't expose
  * usable tokens.
  */
final case class TokenIssuer(
    accessTtl: FiniteDuration,
    refreshTtl: FiniteDuration
) {

  private val random = new SecureRandom()
  private val tokenBytes = 32
  private val encoder = Base64.getUrlEncoder.withoutPadding()

  def issue[F[_]: Sync](kind: TokenKind): F[IssuedToken] = Sync[F].delay {
    val bytes = new Array[Byte](tokenBytes)
    random.nextBytes(bytes)
    val value = encoder.encodeToString(bytes)
    val ttl = kind match {
      case TokenKind.Access  => accessTtl
      case TokenKind.Refresh => refreshTtl
    }
    IssuedToken(
      value = value,
      hash = hash(value),
      kind = kind,
      expiresAt = Instant.now().plusSeconds(ttl.toSeconds)
    )
  }

  def hash(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest
      .digest(value.getBytes("UTF-8"))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
  }
}
