package userservice

import cats.effect.Sync

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 password hashing (US-16). Stored form:
  * `pbkdf2$<iterations>$<salt base64url>$<hash base64url>` — the iteration
  * count travels with the hash so it can be raised later without breaking old
  * rows. Never log or return the plaintext or the stored value.
  */
final case class PasswordHasher(iterations: Int) {

  private val random = new SecureRandom()
  private val keyLengthBits = 256
  private val saltLength = 16
  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder

  def hash[F[_]: Sync](password: String): F[String] = Sync[F].delay {
    val salt = new Array[Byte](saltLength)
    random.nextBytes(salt)
    val digest = pbkdf2(password, salt, iterations)
    s"pbkdf2$$$iterations$$${encoder.encodeToString(salt)}$$${encoder.encodeToString(digest)}"
  }

  def verify[F[_]: Sync](password: String, stored: String): F[Boolean] =
    Sync[F].delay {
      stored.split("\\$") match {
        case Array("pbkdf2", iterationsPart, saltPart, hashPart) =>
          scala.util
            .Try {
              val salt = decoder.decode(saltPart)
              val expected = decoder.decode(hashPart)
              MessageDigest.isEqual(
                expected,
                pbkdf2(password, salt, iterationsPart.toInt)
              )
            }
            .getOrElse(false)
        case _ => false
      }
    }

  private def pbkdf2(
      password: String,
      salt: Array[Byte],
      rounds: Int
  ): Array[Byte] = {
    val spec =
      new PBEKeySpec(password.toCharArray, salt, rounds, keyLengthBits)
    try
      SecretKeyFactory
        .getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(spec)
        .getEncoded
    finally spec.clearPassword()
  }
}
