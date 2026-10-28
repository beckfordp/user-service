package userservice

import cats.effect.IO
import munit.CatsEffectSuite

import java.time.Instant

class UserStoreSuite extends CatsEffectSuite {

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
      id: String,
      userId: String,
      kind: TokenKind,
      expiresAt: Instant = Instant.parse("2026-10-28T13:00:00Z")
  ): NewToken =
    NewToken(id, userId, s"hash-$id", kind.wire, expiresAt)

  test("createAccount stores the account and its access token") {
    for {
      (store, outbox) <- UserStore.inMemoryWithOutbox[IO]
      result <- store.createAccount(
        "user-1",
        "ada@example.com",
        "pbkdf2$1000$salt$hash",
        token("token-1", "user-1", TokenKind.Access),
        envelope
      )
      found <- store.findByEmail("ada@example.com")
      events <- outbox.get
    } yield {
      assertEquals(result.map(_.id), Right("user-1"))
      assertEquals(found.map(_.email), Some("ada@example.com"))
      assertEquals(events.map(_._1), List("user-1"))
      assertEquals(events.map(_._2.eventType), List("UserRegistered"))
    }
  }

  test("createAccount rejects a duplicate email and writes nothing extra") {
    for {
      (store, outbox) <- UserStore.inMemoryWithOutbox[IO]
      _ <- store.createAccount(
        "user-1",
        "ada@example.com",
        "hash-a",
        token("token-1", "user-1", TokenKind.Access),
        envelope
      )
      duplicate <- store.createAccount(
        "user-2",
        "ada@example.com",
        "hash-b",
        token("token-2", "user-2", TokenKind.Access),
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
        "user-1",
        "ada@example.com",
        "stored-hash",
        token("token-1", "user-1", TokenKind.Access),
        envelope
      )
      found <- store.findByEmail("ada@example.com")
      missing <- store.findByEmail("unknown@example.com")
    } yield {
      assertEquals(found.map(_.passwordHash), Some("stored-hash"))
      assertEquals(missing, None)
    }
  }

  test("recordLoginTokens accepts the access and refresh pair") {
    for {
      store <- UserStore.inMemory[IO]
      _ <- store.createAccount(
        "user-1",
        "ada@example.com",
        "hash",
        token("token-1", "user-1", TokenKind.Access),
        envelope
      )
      result <- store.recordLoginTokens(
        token("token-2", "user-1", TokenKind.Access),
        token("token-3", "user-1", TokenKind.Refresh)
      )
    } yield assertEquals(result, ())
  }
}
