// SPDX-License-Identifier: MIT

package userservice.domain

final case class SignupCredentials(email: Email, password: Password)

final case class LoginCredentials(email: Email, password: Password)

/** Parses raw signup/login input once, at the HTTP boundary: the email first,
  * then the password.
  */
object Credentials {

  def parseSignup(
      email: String,
      password: String
  ): Either[SignupError, SignupCredentials] =
    for {
      parsedEmail <- Email.parse(email)
      parsedPassword <- Password.forSignup(password)
    } yield SignupCredentials(parsedEmail, parsedPassword)

  def parseLogin(
      email: String,
      password: String
  ): Either[LoginError, LoginCredentials] =
    for {
      parsedEmail <- Email.parse(email)
      parsedPassword <- Password.forLogin(password)
    } yield LoginCredentials(parsedEmail, parsedPassword)
}
