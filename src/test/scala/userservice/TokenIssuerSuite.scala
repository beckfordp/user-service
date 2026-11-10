// SPDX-License-Identifier: MIT

package userservice

import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite
import userservice.domain.TokenKind

import java.time.Instant
import scala.concurrent.duration._

class TokenIssuerSuite extends CatsEffectSuite {

  private val issuer: IO[TokenIssuer[IO]] =
    TokenIssuer.secureRandom[IO](accessTtl = 1.hour, refreshTtl = 30.days)

  test("an access token is opaque, hashed, and expires in one hour") {
    for {
      before <- IO.realTimeInstant
      token <- issuer.flatMap(_.issue(TokenKind.Access))
    } yield {
      assertEquals(token.kind, TokenKind.Access)
      assert(token.value.length >= 40)
      assertEquals(token.hash, TokenIssuer.hash(token.value))
      assertNotEquals(token.hash, token.value)
      assert(!token.expiresAt.isBefore(before.plusSeconds(3595)))
      assert(!token.expiresAt.isAfter(before.plusSeconds(3605)))
    }
  }

  test("expiry is computed from Clock[F], not the wall clock") {
    TestControl
      .executeEmbed(
        for {
          tokens <- issuer
          access <- tokens.issue(TokenKind.Access)
          refresh <- tokens.issue(TokenKind.Refresh)
        } yield (access.expiresAt, refresh.expiresAt)
      )
      .map { case (access, refresh) =>
        assertEquals(access, Instant.EPOCH.plusSeconds(3600))
        assertEquals(refresh, Instant.EPOCH.plusSeconds(30L * 24 * 3600))
      }
  }

  test("a refresh token expires in 30 days") {
    for {
      before <- IO.realTimeInstant
      token <- issuer.flatMap(_.issue(TokenKind.Refresh))
    } yield {
      assertEquals(token.kind, TokenKind.Refresh)
      assert(!token.expiresAt.isBefore(before.plusSeconds(30 * 24 * 3600 - 5)))
      assert(!token.expiresAt.isAfter(before.plusSeconds(30 * 24 * 3600 + 5)))
    }
  }

  test("each issued token is unique") {
    for {
      tokens <- issuer
      first <- tokens.issue(TokenKind.Access)
      second <- tokens.issue(TokenKind.Access)
    } yield assertNotEquals(first.value, second.value)
  }

  test("the hash is a stable hex SHA-256 of the raw value") {
    assertEquals(
      TokenIssuer.hash("abc"),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
  }
}
