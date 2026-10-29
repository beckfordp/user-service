package userservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{HttpRoutes, Method, Request, Response, Status}
import org.typelevel.log4cats.noop.NoOpLogger

import scala.concurrent.duration._

class AuthRoutesSuite extends CatsEffectSuite {

  private val hasher = PasswordHasher(iterations = 1000)
  private val tokens = TokenIssuer(accessTtl = 1.hour, refreshTtl = 30.days)

  private final case class Fixture(
      store: UserStore[IO],
      outbox: cats.effect.Ref[IO, List[(String, EventEnvelope)]]
  ) {
    val routes: HttpRoutes[IO] =
      AuthRoutes.routes[IO](store, hasher, tokens, NoOpLogger[IO])

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
    UserStore
      .inMemoryWithOutbox[IO]
      .map { case (store, outbox) => Fixture(store, outbox) }

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
}
