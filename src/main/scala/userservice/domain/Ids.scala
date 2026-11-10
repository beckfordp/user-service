// SPDX-License-Identifier: MIT

package userservice.domain

import cats.syntax.all._
import sttp.tapir.{Codec, CodecFormat, DecodeResult, Schema}

import java.util.UUID

/** Opaque account identifier (the user id exposed to clients). Minted inside
  * `F` and passed through stores typed; raw HTTP input is parsed exactly once,
  * at the boundary (parse, don't validate — scala-fp.md law 5).
  */
opaque type AccountId = UUID

object AccountId {
  def parse(raw: String): Either[String, AccountId] =
    Either
      .catchOnly[IllegalArgumentException](UUID.fromString(raw))
      .leftMap(_ => s"Invalid user id: '$raw'")

  def fromUuid(value: UUID): AccountId = value
  def uuid(value: AccountId): UUID = value
  def render(value: AccountId): String = value.toString

  given Codec[String, AccountId, CodecFormat.TextPlain] =
    Codec.string.mapDecode(raw =>
      parse(raw) match {
        case Right(id) => DecodeResult.Value(id)
        case Left(msg) =>
          DecodeResult.Error(raw, new IllegalArgumentException(msg))
      }
    )(render)

  given Schema[AccountId] = Schema.string[AccountId]
}

/** Opaque token-row identifier, distinct from [[AccountId]]. */
opaque type TokenId = UUID

object TokenId {
  def fromUuid(value: UUID): TokenId = value
  def uuid(value: TokenId): UUID = value
  def render(value: TokenId): String = value.toString
}
