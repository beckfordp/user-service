package userservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.docs.Docs
import sttp.tapir.EndpointOutput
import sttp.tapir.server.ServerEndpoint

import scala.concurrent.duration._

class UserDocsSuite extends CatsEffectSuite {

  /** The same endpoints `AppRoutes.all` passes to `Docs.routes`. */
  private def endpoints: IO[List[ServerEndpoint[Any, IO]]] =
    for {
      store <- UserStore.inMemory[IO]
      hasher <- PasswordHasher.pbkdf2[IO](iterations = 1000)
      tokens <- TokenIssuer.secureRandom[IO](1.hour, 30.days)
    } yield List(
      AuthRoutes.signupServerEndpoint[IO](
        store,
        hasher,
        tokens,
        NoOpLogger[IO]
      ),
      AuthRoutes.loginServerEndpoint[IO](
        store,
        hasher,
        tokens,
        NoOpLogger[IO]
      ),
      HealthRoutes.healthServerEndpoint[IO],
      HealthRoutes.readyServerEndpoint[IO](store)
    )

  test("every endpoint is documented") {
    endpoints.map { serverEndpoints =>
      assertEquals(
        Docs.undocumented(serverEndpoints.map(_.endpoint)).map(_.render),
        Nil
      )
    }
  }

  /** Every fixed status an output can produce (tapir's own traversal is
    * internal).
    */
  private def statuses(output: EndpointOutput[?]): List[Int] =
    output match {
      case EndpointOutput.FixedStatusCode(code, _, _) => List(code.code)
      case EndpointOutput.OneOf(variants, _)          =>
        variants.flatMap(v => statuses(v.output))
      case EndpointOutput.Pair(left, right, _, _) =>
        statuses(left) ++ statuses(right)
      case EndpointOutput.MappedPair(pair, _) => statuses(pair)
      case _                                  => Nil
    }

  test("each endpoint documents only the errors it can return") {
    endpoints.map { serverEndpoints =>
      assertEquals(
        serverEndpoints.map(endpoint =>
          statuses(endpoint.endpoint.errorOutput).sorted
        ),
        List(
          List(400, 400, 409),
          List(400, 400, 401),
          Nil,
          List(503)
        )
      )
    }
  }
}
