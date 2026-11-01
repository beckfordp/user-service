package userservice.domain

/** Kind of an issued token; `wire` is the `auth_tokens.kind` column value. */
sealed trait TokenKind {
  def wire: String
}

object TokenKind {
  case object Access extends TokenKind { val wire = "access" }
  case object Refresh extends TokenKind { val wire = "refresh" }
}
