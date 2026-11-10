// SPDX-License-Identifier: MIT

package userservice

import cats.effect.Sync
import cats.syntax.all._
import userservice.domain.Password

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Outcome of checking a password against a stored hash. `Malformed` (a corrupt
  * or foreign row) is distinct from `Mismatch` so login can log it, though the
  * caller still gets the generic 401.
  */
sealed trait PasswordCheck

object PasswordCheck {
  case object Match extends PasswordCheck
  case object Mismatch extends PasswordCheck
  case object Malformed extends PasswordCheck
}

/** Password hashing algebra (US-16). Never log or return the plaintext or the
  * stored value.
  */
trait PasswordHasher[F[_]] {

  /** A new stored hash for `password`, with a fresh random salt. */
  def hash(password: Password): F[String]

  /** Checks `password` against `stored` (constant-time comparison). `None` (no
    * such account) still runs one full key derivation against a dummy hash and
    * answers `Mismatch`, so response time doesn't reveal which emails exist.
    */
  def verify(password: Password, stored: Option[String]): F[PasswordCheck]
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
    Sync[F]
      .delay {
        val random = new SecureRandom()
        val dummySalt = new Array[Byte](saltLength)
        val dummyHash = new Array[Byte](keyLengthBits / 8)
        random.nextBytes(dummySalt)
        random.nextBytes(dummyHash)
        (random, dummySalt, dummyHash)
      }
      .map { case (random, dummySalt, dummyHash) =>
        val encoder = Base64.getUrlEncoder.withoutPadding()

        // The same work as a real check, for a missing or unreadable row.
        def dummyCheck(password: Password): Unit = {
          val _ = MessageDigest.isEqual(
            dummyHash,
            derive(password, dummySalt, iterations)
          )
        }

        new PasswordHasher[F] {
          def hash(password: Password): F[String] = Sync[F].delay {
            val salt = new Array[Byte](saltLength)
            random.nextBytes(salt)
            val digest = derive(password, salt, iterations)
            s"pbkdf2$$$iterations$$${encoder.encodeToString(salt)}$$${encoder.encodeToString(digest)}"
          }

          def verify(
              password: Password,
              stored: Option[String]
          ): F[PasswordCheck] =
            Sync[F].delay {
              stored.map(parseStored) match {
                case None =>
                  dummyCheck(password)
                  PasswordCheck.Mismatch
                case Some(None) =>
                  dummyCheck(password)
                  PasswordCheck.Malformed
                case Some(Some(parsed)) =>
                  if (
                    MessageDigest.isEqual(
                      parsed.hash,
                      derive(password, parsed.salt, parsed.iterations)
                    )
                  ) PasswordCheck.Match
                  else PasswordCheck.Mismatch
              }
            }
        }
      }

  private final case class StoredHash(
      iterations: Int,
      salt: Array[Byte],
      hash: Array[Byte]
  )

  private def decode(part: String): Option[Array[Byte]] =
    Either
      .catchOnly[IllegalArgumentException](Base64.getUrlDecoder.decode(part))
      .toOption
      .filter(_.nonEmpty)

  /** `pbkdf2$<iterations>$<salt>$<hash>`; anything else is malformed. The
    * wildcard is over raw split parts (an open set), not a sealed type.
    */
  private def parseStored(stored: String): Option[StoredHash] =
    stored.split("\\$") match {
      case Array("pbkdf2", iterationsPart, saltPart, hashPart) =>
        for {
          iterations <- iterationsPart.toIntOption.filter(_ > 0)
          salt <- decode(saltPart)
          hash <- decode(hashPart)
        } yield StoredHash(iterations, salt, hash)
      case _ => None
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
