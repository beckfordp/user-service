package userservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.docs.Docs

class UserDocsSuite extends CatsEffectSuite {

  test(
    "the tapir-described endpoint is served and documented via purerest.docs"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      endpoint = UserRoutes.serverEndpoint[IO](store, NoOpLogger[IO])
      listEndpoint =
        UserRoutes.listUsersServerEndpoint[IO](
          store,
          NoOpLogger[IO]
        )
      routes = Docs.routes[IO](
        "User Service",
        "1.0",
        List(endpoint, listEndpoint)
      )
      request = Request[IO](Method.POST, uri"/users")
        .withEntity(CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */))
      response <- routes.orNotFound.run(request)
      entity <- response.as[UserResponse]
      docsResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/docs.yaml")
      )
      docsBody <- docsResponse.bodyText.compile.string
    } yield {
      assertEquals(response.status, Status.Created)
      assert(entity.id.nonEmpty)
      assertEquals(docsResponse.status, Status.Ok)
      assert(clue(docsBody).contains("/users"))
      assert(clue(docsBody).contains("X-Total-Count"))
    }
  }
}
