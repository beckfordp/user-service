// SPDX-License-Identifier: MIT

package userservice

import cats.effect.IO
import munit.CatsEffectSuite
import purerest.metrics.Metrics
import userservice.domain.{AccountId, TokenId, TokenKind}

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters._

class UserJourneyMetricsSuite extends CatsEffectSuite {

  private val envelope = EventEnvelope(
    "event-1",
    "UserRegistered",
    Instant.parse("2026-10-28T12:00:00Z"),
    "user-service",
    "user-1",
    io.circe.Json.obj()
  )

  private def token(user: AccountId, kind: TokenKind) =
    NewToken(
      TokenId.fromUuid(UUID.randomUUID()),
      user,
      "hash",
      kind,
      Instant.parse("2099-01-01T00:00:00Z")
    )

  test("counts registrations by outcome and successful logins") {
    Metrics.test[IO]("user-journey").use { tm =>
      val (a, b) =
        (
          AccountId.fromUuid(UUID.randomUUID()),
          AccountId.fromUuid(UUID.randomUUID())
        )
      for {
        underlying <- UserStore.inMemory[IO]
        store <- UserJourneyMetrics.instrument[IO](underlying, tm.meter)
        email = TestValues.email("ada@example.com")
        _ <- store.createAccount(
          a,
          email,
          "h",
          token(a, TokenKind.Access),
          envelope
        )
        _ <- store.createAccount(
          b,
          email,
          "h",
          token(b, TokenKind.Access),
          envelope
        )
        _ <- store.recordLoginTokens(
          token(a, TokenKind.Access),
          token(a, TokenKind.Refresh)
        )
        found <- store.findByEmail(email)
        metrics <- tm.collectMetrics
      } yield {
        assert(found.isDefined)
        val registrations = metrics
          .find(_.getName == "users.registrations")
          .get
          .getLongSumData
          .getPoints
          .asScala
          .map(p =>
            p.getAttributes.asMap.asScala.values.head.toString -> p.getValue
          )
          .toMap
        assertEquals(registrations, Map("created" -> 1L, "duplicate" -> 1L))
        val logins = metrics.find(_.getName == "users.logins").get
        assertEquals(
          logins.getLongSumData.getPoints.asScala.map(_.getValue).sum,
          1L
        )
      }
    }
  }
}
