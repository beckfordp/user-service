package userservice.domain

import java.util.UUID

/** Opaque account identifier (the user id exposed to clients). Minted inside
  * `F` and passed through stores typed; never re-parsed from a raw String.
  */
opaque type AccountId = UUID

object AccountId {
  def fromUuid(value: UUID): AccountId = value
  def uuid(value: AccountId): UUID = value
  def render(value: AccountId): String = value.toString
}

/** Opaque token-row identifier, distinct from [[AccountId]]. */
opaque type TokenId = UUID

object TokenId {
  def fromUuid(value: UUID): TokenId = value
  def uuid(value: TokenId): UUID = value
  def render(value: TokenId): String = value.toString
}
