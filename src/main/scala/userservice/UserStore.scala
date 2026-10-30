package userservice

import cats.effect.{Async, Ref, Resource, Sync}
import cats.effect.std.Console
import cats.syntax.all._
import userservice.domain.{AccountId, TokenId}
import fs2.io.net.Network
import io.circe.syntax._
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import purerest.metrics.LatencyBuckets
import skunk.Session
import skunk.circe.codec.all.jsonb
import skunk.codec.all._
import skunk.implicits._

import java.time.{Instant, OffsetDateTime}
import java.util.UUID
import scala.concurrent.duration.SECONDS

final case class UserAccount(
    id: AccountId,
    email: String,
    createdAt: Instant
)

/** Internal lookup row for login: carries the stored password hash. */
final case class StoredAccount(
    id: AccountId,
    email: String,
    passwordHash: String
)

/** A token about to be persisted; its raw value never reaches the store. */
final case class NewToken(
    id: TokenId,
    userId: AccountId,
    hash: String,
    kind: String,
    expiresAt: Instant
)

sealed trait CreateAccountError

case object DuplicateEmail extends CreateAccountError

trait UserStore[F[_]] {
  def ping: F[Boolean]

  /** Creates the account, its access token and the `UserRegistered` outbox row
    * in one transaction (US-16.1/16.3). A duplicate email writes nothing.
    */
  def createAccount(
      id: AccountId,
      email: String,
      passwordHash: String,
      accessToken: NewToken,
      outbox: EventEnvelope
  ): F[Either[CreateAccountError, UserAccount]]

  def findByEmail(email: String): F[Option[StoredAccount]]

  def recordLoginTokens(
      accessToken: NewToken,
      refreshToken: NewToken
  ): F[Unit]
}

object UserStore {

  def inMemory[F[_]: Sync]: F[UserStore[F]] =
    inMemoryWithOutbox[F].map(_._1)

  /** In-memory interpreter plus the outbox rows it captured, for tests. */
  def inMemoryWithOutbox[F[_]: Sync]
      : F[(UserStore[F], Ref[F, List[(String, EventEnvelope)]])] =
    for {
      accounts <- Ref.of[F, Map[AccountId, StoredAccount]](Map.empty)
      tokens <- Ref.of[F, List[NewToken]](Nil)
      outbox <- Ref.of[F, List[(String, EventEnvelope)]](Nil)
    } yield {
      val store = new UserStore[F] {
        def ping: F[Boolean] = Sync[F].pure(true)

        def createAccount(
            id: AccountId,
            email: String,
            passwordHash: String,
            accessToken: NewToken,
            envelope: EventEnvelope
        ): F[Either[CreateAccountError, UserAccount]] =
          accounts
            .modify { current =>
              if (current.values.exists(_.email == email)) {
                (current, Left(DuplicateEmail))
              } else {
                (
                  current + (id -> StoredAccount(id, email, passwordHash)),
                  Right(UserAccount(id, email, Instant.now()))
                )
              }
            }
            .flatMap {
              case Right(account) =>
                tokens.update(_ :+ accessToken) *>
                  outbox
                    .update(_ :+ (AccountId.render(id) -> envelope))
                    .as(Right(account))
              case Left(error) => Sync[F].pure(Left(error))
            }

        def findByEmail(email: String): F[Option[StoredAccount]] =
          accounts.get.map(_.values.find(_.email == email))

        def recordLoginTokens(
            accessToken: NewToken,
            refreshToken: NewToken
        ): F[Unit] =
          tokens.update(_ :+ accessToken :+ refreshToken)
      }
      (store, outbox)
    }

  private val insertAccount: skunk.Query[
    (UUID, String, String),
    OffsetDateTime
  ] =
    sql"""
      INSERT INTO "user_account" (id, email, password_hash)
      VALUES ($uuid, $text, $text)
      ON CONFLICT (email) DO NOTHING
      RETURNING created_at
    """.query(timestamptz)

  private val insertToken: skunk.Command[
    (UUID, UUID, String, String, OffsetDateTime)
  ] =
    sql"""
      INSERT INTO "auth_tokens" (id, user_id, token_hash, kind, expires_at)
      VALUES ($uuid, $uuid, $text, $text, $timestamptz)
    """.command

  private val selectByEmail: skunk.Query[String, (UUID, String, String)] =
    sql"""
      SELECT id, email, password_hash
      FROM "user_account"
      WHERE email = $text
    """.query(uuid *: text *: text)

  private val insertOutboxEvent
      : skunk.Command[(UUID, String, EventEnvelope, String)] =
    sql"""
      INSERT INTO outbox_events (id, aggregate_id, payload, correlation_id)
      VALUES ($uuid, $text, ${jsonb[EventEnvelope]}, $text)
    """.command

  private val pingQuery: skunk.Query[skunk.Void, Int] = sql"SELECT 1".query(
    int4
  )

  def postgres[F[_]: Async: Console: Network](
      config: PostgresConfig,
      meter: Meter[F]
  ): Resource[F, UserStore[F]] = {
    import org.typelevel.otel4s.trace.Tracer.Implicits.noop
    import org.typelevel.otel4s.metrics.Meter.Implicits.noop
    Session
      .Builder[F]
      .withHost(config.host)
      .withPort(config.port)
      .withUserAndPassword(config.user, config.password)
      .withDatabase(config.database)
      .pooled(max = 10)
      .evalMap { pool =>
        meter
          .histogram[Double]("db.client.operation.duration")
          .withUnit("s")
          .withExplicitBucketBoundaries(LatencyBuckets.seconds)
          .create
          .map { histogram =>
            def timed[A](operation: String)(fa: F[A]): F[A] =
              for {
                start <- Async[F].monotonic
                result <- fa.attempt
                end <- Async[F].monotonic
                outcomeAttributes = result match {
                  case Right(_)    => Nil
                  case Left(error) =>
                    List(Attribute("error.type", error.getClass.getName))
                }
                _ <- histogram.record(
                  (end - start).toUnit(SECONDS),
                  List(
                    Attribute("db.system", "postgresql"),
                    Attribute("db.operation", operation)
                  ) ++ outcomeAttributes
                )
                a <- result.liftTo[F]
              } yield a

            def storeToken(session: Session[F], token: NewToken): F[Unit] =
              for {
                tokenId = TokenId.uuid(token.id)
                userId = AccountId.uuid(token.userId)
                expiresAt = token.expiresAt.atOffset(java.time.ZoneOffset.UTC)
                _ <- session
                  .prepare(insertToken)
                  .flatMap(
                    _.execute(
                      (tokenId, userId, token.hash, token.kind, expiresAt)
                    )
                  )
              } yield ()

            new UserStore[F] {
              def ping: F[Boolean] =
                timed("ping")(pool.use(_.unique(pingQuery))).attempt
                  .map(_.isRight)

              def createAccount(
                  id: AccountId,
                  email: String,
                  passwordHash: String,
                  accessToken: NewToken,
                  envelope: EventEnvelope
              ): F[Either[CreateAccountError, UserAccount]] =
                timed("insert") {
                  pool.use { session =>
                    session.transaction.use { _ =>
                      for {
                        accountId = AccountId.uuid(id)
                        createdAt <- session
                          .prepare(insertAccount)
                          .flatMap(
                            _.option((accountId, email, passwordHash))
                          )
                        result <- createdAt match {
                          case None     => Sync[F].pure(Left(DuplicateEmail))
                          case Some(at) =>
                            for {
                              _ <- storeToken(session, accessToken)
                              outboxId <- Sync[F].delay(UUID.randomUUID())
                              _ <- session
                                .prepare(insertOutboxEvent)
                                .flatMap(
                                  _.execute(
                                    (
                                      outboxId,
                                      AccountId.render(id),
                                      envelope,
                                      envelope.correlationId
                                    )
                                  )
                                )
                            } yield Right(
                              UserAccount(id, email, at.toInstant)
                            )
                        }
                      } yield result
                    }
                  }
                }

              def findByEmail(
                  email: String
              ): F[Option[StoredAccount]] =
                timed("select") {
                  pool.use { session =>
                    session
                      .prepare(selectByEmail)
                      .flatMap(_.option(email))
                  }
                }.map(_.map { case (id, storedEmail, passwordHash) =>
                  StoredAccount(
                    AccountId.fromUuid(id),
                    storedEmail,
                    passwordHash
                  )
                })

              def recordLoginTokens(
                  accessToken: NewToken,
                  refreshToken: NewToken
              ): F[Unit] =
                timed("insert") {
                  pool.use { session =>
                    session.transaction.use { _ =>
                      storeToken(session, accessToken) *>
                        storeToken(session, refreshToken)
                    }
                  }
                }
            }
          }
      }
  }
}
