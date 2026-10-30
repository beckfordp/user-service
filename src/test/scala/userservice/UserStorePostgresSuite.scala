package userservice

import cats.effect.IO
import cats.syntax.all._
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import munit.CatsEffectSuite
import userservice.domain.{AccountId, TokenId}

import java.util.UUID
import org.testcontainers.utility.DockerImageName
import org.typelevel.otel4s.metrics.Meter
import purerest.metrics.{LatencyBuckets, Metrics}

import scala.jdk.CollectionConverters._

import java.sql.DriverManager
import java.time.Instant

class UserStorePostgresSuite extends CatsEffectSuite with TestContainerForAll {

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(dockerImageName =
      DockerImageName.parse("postgres:16-alpine")
    )

  private def configFor(postgres: PostgreSQLContainer): PostgresConfig =
    PostgresConfig(
      host = postgres.host,
      port = postgres.mappedPort(5432),
      database = postgres.databaseName,
      user = postgres.username,
      password = postgres.password
    )

  private def withStore[A](postgres: PostgreSQLContainer)(
      f: UserStore[IO] => IO[A]
  ): IO[A] =
    Migrations.run[IO](configFor(postgres)) *>
      UserStore
        .postgres[IO](configFor(postgres), Meter.noop[IO])
        .use(f)

  private def query(
      postgres: PostgreSQLContainer,
      sql: String
  ): IO[List[String]] =
    IO.blocking {
      val conn = DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username,
        postgres.password
      )
      try {
        val rs = conn.createStatement().executeQuery(sql)
        Iterator
          .unfold(())(_ => if (rs.next()) Some((rs.getString(1), ())) else None)
          .toList
      } finally conn.close()
    }

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
      kind: TokenKind
  ): NewToken =
    NewToken(
      id,
      userId,
      s"hash-${TokenId.render(id)}",
      kind.wire,
      Instant.now().plusSeconds(3600)
    )

  private def uniqueId(): AccountId = AccountId.fromUuid(UUID.randomUUID())
  private def uniqueTokenId(): TokenId = TokenId.fromUuid(UUID.randomUUID())

  test("createAccount writes the account, token and outbox row atomically") {
    withContainers { postgres =>
      withStore(postgres) { store =>
        val userId = uniqueId()
        val tokenId = uniqueTokenId()
        for {
          result <- store.createAccount(
            userId,
            "ada@example.com",
            "pbkdf2$1000$salt$hash",
            token(tokenId, userId, TokenKind.Access),
            envelope.copy(correlationId = AccountId.render(userId))
          )
          tokens <- query(
            postgres,
            s"select id from auth_tokens where user_id = '$userId'"
          )
          outbox <- query(
            postgres,
            s"select event_type from (select payload->>'eventType' as event_type from outbox_events where aggregate_id = '$userId') t"
          )
        } yield {
          assertEquals(result.map(_.id), Right(userId))
          assertEquals(tokens, List(TokenId.render(tokenId)))
          assertEquals(outbox, List("UserRegistered"))
        }
      }
    }
  }

  test("a duplicate email writes no extra token or outbox row") {
    withContainers { postgres =>
      withStore(postgres) { store =>
        val firstUser = uniqueId()
        val secondUser = uniqueId()
        for {
          _ <- store.createAccount(
            firstUser,
            "dup@example.com",
            "hash-a",
            token(uniqueTokenId(), firstUser, TokenKind.Access),
            envelope
          )
          duplicate <- store.createAccount(
            secondUser,
            "dup@example.com",
            "hash-b",
            token(uniqueTokenId(), secondUser, TokenKind.Access),
            envelope
          )
          users <- query(
            postgres,
            "select id from user_account where email = 'dup@example.com'"
          )
        } yield {
          assertEquals(duplicate, Left(DuplicateEmail))
          assertEquals(users.size, 1)
        }
      }
    }
  }

  test("findByEmail returns the stored password hash") {
    withContainers { postgres =>
      withStore(postgres) { store =>
        for {
          userId <- IO(uniqueId())
          _ <- store.createAccount(
            userId,
            "lookup@example.com",
            "stored-hash",
            token(uniqueTokenId(), userId, TokenKind.Access),
            envelope
          )
          found <- store.findByEmail("lookup@example.com")
          missing <- store.findByEmail("missing@example.com")
        } yield {
          assertEquals(found.map(_.passwordHash), Some("stored-hash"))
          assertEquals(missing, None)
        }
      }
    }
  }

  test("recordLoginTokens stores both tokens") {
    withContainers { postgres =>
      withStore(postgres) { store =>
        val userId = uniqueId()
        val accessId = uniqueTokenId()
        val refreshId = uniqueTokenId()
        for {
          _ <- store.createAccount(
            userId,
            "login@example.com",
            "hash",
            token(uniqueTokenId(), userId, TokenKind.Access),
            envelope
          )
          _ <- store.recordLoginTokens(
            token(accessId, userId, TokenKind.Access),
            token(refreshId, userId, TokenKind.Refresh)
          )
          kinds <- query(
            postgres,
            s"select kind from auth_tokens where user_id = '$userId' and id in ('$accessId', '$refreshId') order by kind"
          )
        } yield assertEquals(kinds, List("access", "refresh"))
      }
    }
  }

  test("createAccount rolls back the account when the outbox write fails") {
    withContainers { postgres =>
      withStore(postgres) { store =>
        val userId = uniqueId()
        for {
          _ <- IO.blocking {
            val conn = DriverManager.getConnection(
              postgres.jdbcUrl,
              postgres.username,
              postgres.password
            )
            try
              conn
                .createStatement()
                .execute(
                  "alter table outbox_events add constraint reject_all check (false) not valid"
                )
            finally conn.close()
          }
          result <- store
            .createAccount(
              userId,
              "rollback@example.com",
              "hash",
              token(uniqueTokenId(), userId, TokenKind.Access),
              envelope
            )
            .attempt
          _ <- IO.blocking {
            val conn = DriverManager.getConnection(
              postgres.jdbcUrl,
              postgres.username,
              postgres.password
            )
            try
              conn
                .createStatement()
                .execute("alter table outbox_events drop constraint reject_all")
            finally conn.close()
          }
        } yield {
          assert(result.isLeft)
          assertEquals(
            query(
              postgres,
              s"select id from user_account where id = '$userId'"
            ).unsafeRunSync(),
            Nil
          )
        }
      }
    }
  }

  test("ping reports the database is reachable") {
    withContainers { postgres =>
      withStore(postgres)(_.ping.map(ready => assert(ready)))
    }
  }

  test("db.client.operation.duration uses LatencyBuckets.seconds boundaries") {
    withContainers { postgres =>
      Metrics.test[IO]("user-store-buckets-test").use { testMeter =>
        Migrations.run[IO](configFor(postgres)) *>
          UserStore
            .postgres[IO](configFor(postgres), testMeter.meter)
            .use { store =>
              for {
                _ <- store.ping
                metrics <- testMeter.collectMetrics
              } yield {
                val points = metrics
                  .filter(_.getName == "db.client.operation.duration")
                  .flatMap(_.getHistogramData.getPoints.asScala)
                assert(
                  points.nonEmpty,
                  s"expected db.client.operation.duration points, got: $metrics"
                )
                points.foreach { point =>
                  assertEquals(
                    point.getBoundaries.asScala.map(_.doubleValue).toVector,
                    LatencyBuckets.seconds.boundaries
                  )
                }
              }
            }
      }
    }
  }
}
