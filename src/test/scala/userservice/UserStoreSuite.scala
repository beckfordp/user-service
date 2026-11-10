// SPDX-License-Identifier: MIT

package userservice

import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite
import userservice.domain.{AccountId, Email, TokenId, TokenKind}

import java.time.Instant
import java.util.UUID

class UserStoreSuite extends CatsEffectSuite {

  private val accountId = AccountId.fromUuid(UUID.randomUUID())
  private val accountId2 = AccountId.fromUuid(UUID.randomUUID())
  private def newTokenId(): TokenId = TokenId.fromUuid(UUID.randomUUID())

  private val envelope = EventEnvelope(
    eventId = "event-1",
    eventType = "UserRegistered",
    timestamp = Instant.parse("2026-10-28T12:00:00Z"),
    source = "user-service",
    correlationId = "user-1",
    payload = io.circe.Json.obj(
      "userId" -> io.circe.Json.fromString("user-1"),
      "email" -> io.circe.Json.fromString("ada@example.com")
    )
  )

  private def token(
      id: TokenId,
      userId: AccountId,
      kind: TokenKind,
      expiresAt: Instant = Instant.parse("2026-10-28T13:00:00Z")
  ): NewToken =
    NewToken(
      id,
      userId,
      s"hash-${TokenId.render(id)}",
      kind,
      expiresAt
    )

  test("createAccount stores the account and its access token") {
    for {
      (store, outbox) <- UserStore.inMemoryWithOutbox[IO]
      result <- store.createAccount(
        accountId,
        TestValues.email("ada@example.com"),
        "pbkdf2$1000$salt$hash",
        token(newTokenId(), accountId, TokenKind.Access),
        envelope
      )
      found <- store.findByEmail(TestValues.email("ada@example.com"))
      events <- outbox.get
    } yield {
      assertEquals(result.map(_.id), Right(accountId))
      assertEquals(
        found.map(_.email),
        Some(TestValues.email("ada@example.com"))
      )
      assertEquals(events.map(_._1), List(AccountId.render(accountId)))
      assertEquals(events.map(_._2.eventType), List("UserRegistered"))
    }
  }

  test("createAccount rejects a duplicate email and writes nothing extra") {
    for {
      (store, outbox) <- UserStore.inMemoryWithOutbox[IO]
      _ <- store.createAccount(
        accountId,
        TestValues.email("ada@example.com"),
        "hash-a",
        token(newTokenId(), accountId, TokenKind.Access),
        envelope
      )
      duplicate <- store.createAccount(
        accountId2,
        TestValues.email("ada@example.com"),
        "hash-b",
        token(newTokenId(), accountId2, TokenKind.Access),
        envelope
      )
      events <- outbox.get
    } yield {
      assertEquals(duplicate, Left(DuplicateEmail))
      assertEquals(events.size, 1)
    }
  }

  test("findByEmail returns the stored password hash and None when unknown") {
    for {
      store <- UserStore.inMemory[IO]
      _ <- store.createAccount(
        accountId,
        TestValues.email("ada@example.com"),
        "stored-hash",
        token(newTokenId(), accountId, TokenKind.Access),
        envelope
      )
      found <- store.findByEmail(TestValues.email("ada@example.com"))
      missing <- store.findByEmail(TestValues.email("unknown@example.com"))
    } yield {
      assertEquals(found.map(_.passwordHash), Some("stored-hash"))
      assertEquals(missing, None)
    }
  }

  test(
    "findById returns the account without the password hash and None when unknown"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      _ <- store.createAccount(
        accountId,
        TestValues.email("ada@example.com"),
        "stored-hash",
        token(newTokenId(), accountId, TokenKind.Access),
        envelope
      )
      found <- store.findById(accountId)
      missing <- store.findById(accountId2)
    } yield {
      assertEquals(
        found.map(account =>
          (AccountId.render(account.id), Email.render(account.email))
        ),
        Some((AccountId.render(accountId), "ada@example.com"))
      )
      assertEquals(missing, None)
    }
  }

  test("recordLoginTokens accepts the access and refresh pair") {
    for {
      store <- UserStore.inMemory[IO]
      _ <- store.createAccount(
        accountId,
        TestValues.email("ada@example.com"),
        "hash",
        token(newTokenId(), accountId, TokenKind.Access),
        envelope
      )
      result <- store.recordLoginTokens(
        token(newTokenId(), accountId, TokenKind.Access),
        token(newTokenId(), accountId, TokenKind.Refresh)
      )
    } yield assertEquals(result, ())
  }

  test("the in-memory createdAt comes from Clock[F]") {
    TestControl
      .executeEmbed(
        for {
          store <- UserStore.inMemory[IO]
          result <- store.createAccount(
            accountId,
            TestValues.email("clock@example.com"),
            "hash",
            token(newTokenId(), accountId, TokenKind.Access),
            envelope
          )
        } yield result.map(_.createdAt)
      )
      .map(createdAt => assertEquals(createdAt, Right(Instant.EPOCH)))
  }
}
