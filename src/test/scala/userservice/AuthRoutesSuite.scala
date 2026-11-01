package userservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{HttpRoutes, Method, Request, Response, Status}
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import userservice.domain.{AccountId, Password, TokenId, TokenKind}

import scala.concurrent.duration._

class AuthRoutesSuite extends CatsEffectSuite {

  private final case class Fixture(
      store: UserStore[IO],
      outbox: cats.effect.Ref[IO, List[(String, EventEnvelope)]],
      hasher: PasswordHasher[IO],
      tokens: TokenIssuer[IO],
      logger: StructuredLogger[IO] = NoOpLogger[IO]
  ) {
    val routes: HttpRoutes[IO] =
      AuthRoutes.routes[IO](store, hasher, tokens, logger)

    def signup(email: String, password: String): IO[Response[IO]] =
      routes.orNotFound.run(
        Request[IO](Method.POST, uri"/auth/signup")
          .withEntity(SignupRequest(email, password))
      )

    def login(email: String, password: String): IO[Response[IO]] =
      routes.orNotFound.run(
        Request[IO](Method.POST, uri"/auth/login")
          .withEntity(LoginRequest(email, password))
      )
  }

  private val fixture: IO[Fixture] =
    for {
      (store, outbox) <- UserStore.inMemoryWithOutbox[IO]
      hasher <- PasswordHasher.pbkdf2[IO](iterations = 1000)
      tokens <- TokenIssuer.secureRandom[IO](1.hour, 30.days)
    } yield Fixture(store, outbox, hasher, tokens)

  test(
    "signup returns 201 {userId, token} and writes the UserRegistered event"
  ) {
    for {
      f <- fixture
      response <- f.signup("  Ada@Example.COM ", "correct horse")
      body <- response.as[SignupResponse]
      events <- f.outbox.get
    } yield {
      assertEquals(response.status, Status.Created)
      assert(body.userId.nonEmpty)
      assert(body.token.nonEmpty)
      assertEquals(events.size, 1)
      val (aggregateId, envelope) = events.head
      assertEquals(aggregateId, body.userId)
      assertEquals(envelope.eventType, "UserRegistered")
      assertEquals(envelope.source, "user-service")
      assertEquals(envelope.correlationId, body.userId)
      val payload =
        envelope.payload.as[UserRegisteredEvent].toOption.get
      assertEquals(payload.userId, body.userId)
      assertEquals(payload.email, "ada@example.com")
    }
  }

  test("signup rejects an invalid email") {
    for {
      f <- fixture
      response <- f.signup("not-an-email", "correct horse")
      body <- response.as[ErrorResponse]
      events <- f.outbox.get
    } yield {
      assertEquals(response.status, Status.BadRequest)
      assertEquals(body.error, "Enter a valid email address")
      assertEquals(events, Nil)
    }
  }

  test("signup rejects a password shorter than 8 characters") {
    for {
      f <- fixture
      response <- f.signup("ada@example.com", "short")
      body <- response.as[ErrorResponse]
    } yield {
      assertEquals(response.status, Status.BadRequest)
      assertEquals(body.error, "Password must be at least 8 characters")
    }
  }

  test("signup rejects a duplicate email, case-insensitively") {
    for {
      f <- fixture
      _ <- f.signup("ada@example.com", "correct horse")
      response <- f.signup("ADA@example.com", "another horse")
      body <- response.as[ErrorResponse]
      events <- f.outbox.get
    } yield {
      assertEquals(response.status, Status.Conflict)
      assertEquals(body.error, "An account with this email already exists")
      assertEquals(events.size, 1)
    }
  }

  test("login returns 200 {token, refreshToken} for the right password") {
    for {
      f <- fixture
      signup <- f.signup("ada@example.com", "correct horse")
      signupBody <- signup.as[SignupResponse]
      response <- f.login("ada@example.com", "correct horse")
      body <- response.as[LoginResponse]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body.userId, signupBody.userId)
      assert(body.token.nonEmpty)
      assert(body.refreshToken.nonEmpty)
      assertNotEquals(body.token, body.refreshToken)
    }
  }

  test(
    "login returns the same generic 401 for an unknown email and wrong password"
  ) {
    for {
      f <- fixture
      _ <- f.signup("ada@example.com", "correct horse")
      unknown <- f.login("nobody@example.com", "correct horse")
      wrong <- f.login("ada@example.com", "wrong horse")
      unknownBody <- unknown.as[ErrorResponse]
      wrongBody <- wrong.as[ErrorResponse]
    } yield {
      assertEquals(unknown.status, Status.Unauthorized)
      assertEquals(wrong.status, Status.Unauthorized)
      assertEquals(unknownBody, wrongBody)
      assertEquals(unknownBody.error, "Email or password is incorrect")
    }
  }

  test("login rejects a malformed email with 400") {
    for {
      f <- fixture
      response <- f.login("nope", "whatever")
      body <- response.as[ErrorResponse]
    } yield {
      assertEquals(response.status, Status.BadRequest)
      assertEquals(body.error, "Enter a valid email address")
    }
  }

  test("login rejects a blank password with 400") {
    for {
      f <- fixture
      response <- f.login("ada@example.com", "")
      body <- response.as[ErrorResponse]
    } yield {
      assertEquals(response.status, Status.BadRequest)
      assertEquals(body.error, "Enter your password")
    }
  }

  test("signup stores a pbkdf2 hash, never the plaintext password") {
    for {
      f <- fixture
      _ <- f.signup("ada@example.com", "correct-horse-battery-staple")
      stored <- f.store.findByEmail(TestValues.email("ada@example.com"))
    } yield {
      val hash = stored.map(_.passwordHash).getOrElse("")
      assert(clue(hash).startsWith("pbkdf2$"))
      assert(!hash.contains("correct-horse-battery-staple"))
    }
  }

  /** Wraps a hasher, recording which stored values `verify` was asked about. */
  private def recording(
      hasher: PasswordHasher[IO]
  ): IO[(PasswordHasher[IO], IO[List[Option[String]]])] =
    IO.ref(List.empty[Option[String]]).map { calls =>
      val wrapped = new PasswordHasher[IO] {
        def hash(password: Password): IO[String] = hasher.hash(password)
        def verify(
            password: Password,
            stored: Option[String]
        ): IO[PasswordCheck] =
          calls.update(_ :+ stored) *> hasher.verify(password, stored)
      }
      (wrapped, calls.get)
    }

  test("an unknown email still costs one password check (no timing oracle)") {
    for {
      f <- fixture
      (hasher, calls) <- recording(f.hasher)
      response <- f.copy(hasher = hasher).login("nobody@example.com", "x")
      seen <- calls
    } yield {
      assertEquals(response.status, Status.Unauthorized)
      assertEquals(seen, List(None))
    }
  }

  test("a malformed stored hash logs an error and answers the generic 401") {
    val logger = StructuredTestingLogger.impl[IO]()
    val userId = AccountId.fromUuid(java.util.UUID.randomUUID())
    for {
      f <- fixture
      _ <- f.store.createAccount(
        userId,
        TestValues.email("ada@example.com"),
        "corrupt-row",
        NewToken(
          TokenId.fromUuid(java.util.UUID.randomUUID()),
          userId,
          "h",
          TokenKind.Access,
          java.time.Instant.EPOCH
        ),
        UserStoreFixtures.envelope
      )
      response <- f.copy(logger = logger).login("ada@example.com", "anything")
      body <- response.as[ErrorResponse]
      logged <- logger.logged
    } yield {
      assertEquals(response.status, Status.Unauthorized)
      assertEquals(body.error, "Email or password is incorrect")
      val errors = logged.collect {
        case StructuredTestingLogger.ERROR(message, _, ctx) => (message, ctx)
      }
      assertEquals(
        errors.map(_._1),
        Vector("Stored password hash is malformed")
      )
      assertEquals(
        errors.flatMap(_._2.get("user_id")),
        Vector(AccountId.render(userId))
      )
      assert(!logged.exists(_.toString.contains("corrupt-row")))
    }
  }

  test("no log line or context ever carries a password or a raw token") {
    val logger = StructuredTestingLogger.impl[IO]()
    val password = "correct-horse-battery-staple"
    for {
      f0 <- fixture
      f = f0.copy(logger = logger)
      signup <- f.signup("ada@example.com", password)
      signupBody <- signup.as[SignupResponse]
      login <- f.login("ada@example.com", password)
      loginBody <- login.as[LoginResponse]
      _ <- f.login("ada@example.com", "wrong-horse-battery")
      _ <- f.signup("ada@example.com", password)
      _ <- f.signup("ada@example.com", "short")
      _ <- f.login("nobody@example.com", password)
      logged <- logger.logged
    } yield {
      assert(logged.nonEmpty)
      val secrets = List(
        password,
        "wrong-horse-battery",
        signupBody.token,
        loginBody.token,
        loginBody.refreshToken
      )
      logged.foreach { entry =>
        val text = entry.message + entry.ctx.mkString + entry.throwOpt.mkString
        secrets.foreach(secret => assert(!text.contains(secret), clue(entry)))
      }
    }
  }
}
