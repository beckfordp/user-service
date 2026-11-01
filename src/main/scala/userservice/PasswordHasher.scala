package userservice

import cats.effect.Sync
import cats.syntax.all._
import userservice.domain.Password

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Password hashing algebra (US-16). Never log or return the plaintext or the
  * stored value.
  */
trait PasswordHasher[F[_]] {

  /** A new stored hash for `password`, with a fresh random salt. */
  def hash(password: Password): F[String]

  /** Whether `password` matches `stored` (constant-time comparison). */
  def verify(password: Password, stored: String): F[Boolean]
}

object PasswordHasher {

  private val keyLengthBits = 256
  private val saltLength = 16

  /** PBKDF2-HMAC-SHA256. Stored form:
    * `pbkdf2$<iterations>$<salt base64url>$<hash base64url>` — the iteration
    * count travels with the hash so it can be raised later without breaking old
    * rows. The `SecureRandom` is created inside `F`.
    */
  def pbkdf2[F[_]: Sync](iterations: Int): F[PasswordHasher[F]] =
    Sync[F].delay(new SecureRandom()).map { random =>
      val encoder = Base64.getUrlEncoder.withoutPadding()
      val decoder = Base64.getUrlDecoder

      new PasswordHasher[F] {
        def hash(password: Password): F[String] = Sync[F].delay {
          val salt = new Array[Byte](saltLength)
          random.nextBytes(salt)
          val digest = derive(password, salt, iterations)
          s"pbkdf2$$$iterations$$${encoder.encodeToString(salt)}$$${encoder.encodeToString(digest)}"
        }

        def verify(password: Password, stored: String): F[Boolean] =
          Sync[F].delay {
            stored.split("\\$") match {
              case Array("pbkdf2", iterationsPart, saltPart, hashPart) =>
                scala.util
                  .Try {
                    val salt = decoder.decode(saltPart)
                    val expected = decoder.decode(hashPart)
                    MessageDigest.isEqual(
                      expected,
                      derive(password, salt, iterationsPart.toInt)
                    )
                  }
                  .getOrElse(false)
              case _ => false
            }
          }
      }
    }

  private def derive(
      password: Password,
      salt: Array[Byte],
      rounds: Int
  ): Array[Byte] = {
    val spec =
      new PBEKeySpec(password.value.toCharArray, salt, rounds, keyLengthBits)
    try
      SecretKeyFactory
        .getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(spec)
        .getEncoded
    finally spec.clearPassword()
  }
}
