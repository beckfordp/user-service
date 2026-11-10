// SPDX-License-Identifier: MIT

package userservice

import userservice.domain.{Email, Password}

/** Literal domain values for tests (fails fast on a bad literal). */
object TestValues {
  def email(raw: String): Email =
    Email.parse(raw).fold(e => sys.error(s"bad test email $raw: $e"), identity)

  def password(raw: String): Password =
    Password
      .forLogin(raw)
      .fold(e => sys.error(s"bad test password: $e"), identity)
}
