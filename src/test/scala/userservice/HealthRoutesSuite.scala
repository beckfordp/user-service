package userservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}

class HealthRoutesSuite extends CatsEffectSuite {

  private def store(ready: Boolean): UserStore[IO] =
    new UserStore[IO] {
      def ping: IO[Boolean] = IO.pure(ready)
      def createAccount(
          id: String,
          email: String,
          passwordHash: String,
          accessToken: NewToken,
          outbox: EventEnvelope
      ): IO[Either[CreateAccountError, UserAccount]] =
        IO.raiseError(new NotImplementedError())
      def findByEmail(email: String): IO[Option[StoredAccount]] =
        IO.pure(None)
      def recordLoginTokens(
          accessToken: NewToken,
          refreshToken: NewToken
      ): IO[Unit] = IO.unit
    }

  test("GET /health returns 200") {
    val routes = HealthRoutes.routes[IO](store(ready = true))
    for {
      response <- routes.orNotFound.run(Request[IO](Method.GET, uri"/health"))
    } yield assertEquals(response.status, Status.Ok)
  }

  test("GET /health/ready returns 200 when the store is ready") {
    val routes = HealthRoutes.routes[IO](store(ready = true))
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/health/ready")
      )
    } yield assertEquals(response.status, Status.Ok)
  }

  test("GET /health/ready returns 503 when the store is not ready") {
    val routes = HealthRoutes.routes[IO](store(ready = false))
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/health/ready")
      )
    } yield assertEquals(response.status, Status.ServiceUnavailable)
  }
}
