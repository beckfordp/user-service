package userservice

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{HttpRoutes, Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.metrics.Metrics
import purerest.tracing.Tracing

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class AppRoutesSuite extends CatsEffectSuite {

  private def withFixtureDir[A](f: java.nio.file.Path => IO[A]): IO[A] =
    Resource
      .make(
        IO.blocking {
          val dir =
            java.nio.file.Files.createTempDirectory("app-routes-asyncapi")
          java.nio.file.Files.writeString(
            dir.resolve("index.html"),
            "<html><body>asyncapi fixture</body></html>"
          )
          dir
        }
      )(dir =>
        IO.blocking {
          val paths = java.nio.file.Files.walk(dir)
          try
            paths.iterator().asScala.toList.reverse.foreach { path =>
              java.nio.file.Files.deleteIfExists(path)
              ()
            }
          finally paths.close()
        }.void
      )
      .use(f)

  private def withRoutes[A](f: HttpRoutes[IO] => IO[A]): IO[A] =
    withFixtureDir { fixtureDir =>
      (for {
        testTracer <- Tracing.test[IO]("app-routes-suite")
        testMeter <- Metrics.test[IO]("app-routes-suite")
        store <- Resource.eval(UserStore.inMemory[IO])
      } yield AppRoutes.all[IO](
        store,
        Telemetry(testTracer.tracer, testMeter.meter, NoOpLogger[IO]),
        AuthConfig(1.hour, 30.days, 1000),
        AsyncApiChannels.channels,
        staticAsyncApiDocsDir = fixtureDir.toString
      )).use(f)
    }

  test("GET /docs (Swagger UI) resolves, not a 404") {
    withRoutes { routes =>
      routes.orNotFound
        .run(Request[IO](Method.GET, uri"/docs"))
        .map(response => assertNotEquals(response.status, Status.NotFound))
    }
  }

  test(
    "GET /docs/asyncapi.json resolves and contains the registered event type"
  ) {
    withRoutes { routes =>
      for {
        response <- routes.orNotFound.run(
          Request[IO](Method.GET, uri"/docs/asyncapi.json")
        )
        body <- response.bodyText.compile.string
      } yield {
        assertEquals(response.status, Status.Ok)
        assert(
          body.contains("UserRegistered"),
          s"expected the registered channel's eventType in the AsyncAPI doc, got: $body"
        )
      }
    }
  }

  test(
    "GET /docs/asyncapi/index.html serves the configured static HTML directory"
  ) {
    withRoutes { routes =>
      for {
        response <- routes.orNotFound.run(
          Request[IO](Method.GET, uri"/docs/asyncapi/index.html")
        )
        body <- response.bodyText.compile.string
      } yield {
        assertEquals(response.status, Status.Ok)
        assert(body.contains("asyncapi fixture"))
      }
    }
  }

  test("POST /auth/signup resolves and creates an account end-to-end") {
    withRoutes { routes =>
      routes.orNotFound
        .run(
          Request[IO](Method.POST, uri"/auth/signup")
            .withEntity(SignupRequest("ada@example.com", "correct horse"))
        )
        .map(response => assertEquals(response.status, Status.Created))
    }
  }
}
