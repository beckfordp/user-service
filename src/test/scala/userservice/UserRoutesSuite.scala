// SPDX-License-Identifier: MIT

package userservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{HttpRoutes, Method, Request, Status, Uri}
import org.typelevel.log4cats.noop.NoOpLogger
import sttp.tapir.server.http4s.Http4sServerInterpreter
import userservice.domain.{AccountId, TokenId, TokenKind}

import java.time.Instant
import java.util.UUID

class UserRoutesSuite extends CatsEffectSuite {

  private def routes(store: UserStore[IO]): HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      UserRoutes.getUserServerEndpoint[IO](store, NoOpLogger[IO])
    )

  private def get(routes: HttpRoutes[IO], id: String) =
    routes.orNotFound.run(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/users/$id"))
    )

  private def account(id: AccountId): NewToken =
    NewToken(
      id = TokenId.fromUuid(UUID.randomUUID()),
      userId = id,
      hash = "token-hash",
      kind = TokenKind.Access,
      expiresAt = Instant.parse("2026-10-28T13:00:00Z")
    )

  test("GET /users/{id} returns the public identity") {
    val userId = AccountId.fromUuid(UUID.randomUUID())
    for {
      store <- UserStore.inMemory[IO]
      _ <- store.createAccount(
        userId,
        TestValues.email("  Ada@Example.COM "),
        "stored-hash",
        account(userId),
        UserStoreFixtures.envelope
      )
      response <- get(routes(store), AccountId.render(userId))
      body <- response.as[UserResponse]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body.userId, AccountId.render(userId))
      assertEquals(body.email, "ada@example.com")
    }
  }

  test("GET /users/{id} returns 404 for an unknown id") {
    for {
      store <- UserStore.inMemory[IO]
      response <- get(routes(store), UUID.randomUUID().toString)
      body <- response.as[ErrorResponse]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assertEquals(body.error, "User not found")
    }
  }

  test("GET /users/{id} returns 400 for a malformed id") {
    for {
      store <- UserStore.inMemory[IO]
      response <- get(routes(store), "not-a-uuid")
    } yield assertEquals(response.status, Status.BadRequest)
  }
}
