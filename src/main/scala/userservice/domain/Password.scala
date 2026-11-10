// SPDX-License-Identifier: MIT

package userservice.domain

/** A plaintext password on its way to the hasher. `toString` is redacted so it
  * can't leak into a log line or an error message by accident.
  */
final class Password private (val value: String) {
  override def toString: String = "Password(<redacted>)"
}

object Password {
  val MinLength: Int = 8

  /** Signup rule: at least [[MinLength]] characters. */
  def forSignup(
      raw: String
  ): Either[AuthError.PasswordTooShort.type, Password] =
    Either.cond(
      raw.length >= MinLength,
      new Password(raw),
      AuthError.PasswordTooShort
    )

  /** Login rule: non-empty (the hash decides the rest). */
  def forLogin(raw: String): Either[AuthError.PasswordMissing.type, Password] =
    Either.cond(raw.nonEmpty, new Password(raw), AuthError.PasswordMissing)
}
