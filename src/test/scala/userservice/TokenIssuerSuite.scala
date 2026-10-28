package userservice

import cats.effect.IO
import munit.CatsEffectSuite

import scala.concurrent.duration._

class TokenIssuerSuite extends CatsEffectSuite {

  private val issuer = TokenIssuer(
    accessTtl = 1.hour,
    refreshTtl = 30.days
  )

  test("an access token is opaque, hashed, and expires in one hour") {
    for {
      before <- IO.realTimeInstant
      token <- issuer.issue[IO](TokenKind.Access)
    } yield {
      assertEquals(token.kind, TokenKind.Access)
      assert(token.value.length >= 40)
      assertEquals(token.hash, issuer.hash(token.value))
      assertNotEquals(token.hash, token.value)
      assert(!token.expiresAt.isBefore(before.plusSeconds(3595)))
      assert(!token.expiresAt.isAfter(before.plusSeconds(3605)))
    }
  }

  test("a refresh token expires in 30 days") {
    for {
      before <- IO.realTimeInstant
      token <- issuer.issue[IO](TokenKind.Refresh)
    } yield {
      assertEquals(token.kind, TokenKind.Refresh)
      assert(!token.expiresAt.isBefore(before.plusSeconds(30 * 24 * 3600 - 5)))
      assert(!token.expiresAt.isAfter(before.plusSeconds(30 * 24 * 3600 + 5)))
    }
  }

  test("each issued token is unique") {
    for {
      first <- issuer.issue[IO](TokenKind.Access)
      second <- issuer.issue[IO](TokenKind.Access)
    } yield assertNotEquals(first.value, second.value)
  }

  test("the hash is a stable hex SHA-256 of the raw value") {
    assertEquals(
      issuer.hash("abc"),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
  }
}
