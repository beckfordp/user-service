package userservice.domain

import java.util.Locale

/** A normalized email address (trimmed, lower-cased with `Locale.ROOT`, shaped
  * `local@host.tld`). Only `parse` builds one, so the store and events never
  * see raw input.
  */
opaque type Email = String

object Email {
  private val pattern = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$".r

  def parse(raw: String): Either[AuthError.InvalidEmail.type, Email] = {
    val normalized = raw.trim.toLowerCase(Locale.ROOT)
    Either.cond(pattern.matches(normalized), normalized, AuthError.InvalidEmail)
  }

  def render(value: Email): String = value
}
