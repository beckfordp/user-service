package userservice

import cats.effect.IO
import cats.syntax.all._
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.{
  ERROR,
  INFO,
  WARN
}
import org.typelevel.ci._
import purerest.tracing.{ServerTracing, Tracing}

import scala.concurrent.duration._

class UserRoutesSuite extends CatsEffectSuite {

  private def failingStore(error: Throwable): UserStore[IO] =
    new UserStore[IO] {
      def create(/* codegen:fields:CREATE_PARAMS */): IO[User] =
        IO.raiseError(error)
      def get(id: String): IO[Option[User]] = IO.pure(None)
      def update(
          id: String
          /* codegen:fields:UPDATE_PARAMS */
      ): IO[Option[User]] =
        IO.raiseError(error)
      def delete(id: String): IO[Boolean] = IO.raiseError(error)
      def list(
          limit: Int,
          offset: Int
      ): IO[(List[User], Long)] = IO.raiseError(error)
      def ping: IO[Boolean] = IO.raiseError(error)
    }

  test("POST /users returns 201 with the created entity") {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      request = Request[IO](Method.POST, uri"/users")
        .withEntity(CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */))
      response <- routes.orNotFound.run(request)
      entity <- response.as[UserResponse]
    } yield {
      assertEquals(response.status, Status.Created)
      assert(entity.id.nonEmpty)
    }
  }

  test("GET /users/{id} returns 200 with the persisted entity") {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/users").withEntity(
          CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */)
        )
      )
      created <- postResponse.as[UserResponse]
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users" / created.id)
      )
      fetched <- getResponse.as[UserResponse]
    } yield {
      assertEquals(getResponse.status, Status.Ok)
      assertEquals(fetched, created)
    }
  }

  test(
    "GET /users/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users" / "unknown-id")
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "POST /users logs a received-request line and a completed line with structured context"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = UserRoutes.routes[IO](store, testLogger)
      request = Request[IO](Method.POST, uri"/users")
        .withEntity(CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */))
      response <- routes.orNotFound.run(request)
      entity <- response.as[UserResponse]
      logged <- testLogger.logged
    } yield {
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("POST")
        ),
        s"expected a received-request INFO line with method context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("user_id").contains(entity.id)
        ),
        s"expected a completed INFO line with user_id context, got: $infos"
      )
    }
  }

  test(
    "POST /users logs an ERROR with the raised throwable when persisting the entity fails"
  ) {
    val boom = new RuntimeException("boom")
    for {
      testLogger <- IO.pure(StructuredTestingLogger.impl[IO]())
      routes = UserRoutes.routes[IO](failingStore(boom), testLogger)
      request = Request[IO](Method.POST, uri"/users")
        .withEntity(CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */))
      response <- routes.orNotFound.run(request)
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.InternalServerError)
      val errors = logged.collect { case m: ERROR => m }
      assert(
        errors.exists(m => m.throwOpt.contains(boom)),
        s"expected an ERROR line with the raised throwable, got: $errors"
      )
    }
  }

  test(
    "GET /users/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = UserRoutes.routes[IO](store, testLogger)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/users").withEntity(
          CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */)
        )
      )
      created <- postResponse.as[UserResponse]
      _ <- testLogger.logged // drain POST's own log lines before the GET
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users" / created.id)
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(getResponse.status, Status.Ok)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("GET") &&
            m.ctx.get("user_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("user_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test(
    "GET /users/{id} logs a WARN for an unknown id"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = UserRoutes.routes[IO](store, testLogger)
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users" / "unknown-id")
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("user_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test("PATCH /users/{id} returns 200 with the updated entity") {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/users").withEntity(
          CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */)
        )
      )
      created <- postResponse.as[UserResponse]
      patchResponse <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/users" / created.id)
          .withEntity(UpdateUserRequest(/* codegen:fields:TEST_UPDATE_REQUEST_ARGS */))
      )
      updated <- patchResponse.as[UserResponse]
    } yield {
      assertEquals(patchResponse.status, Status.Ok)
      assertEquals(updated.id, created.id)
    }
  }

  test(
    "PATCH /users/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/users" / "unknown-id")
          .withEntity(UpdateUserRequest(/* codegen:fields:TEST_UPDATE_REQUEST_ARGS */))
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "PATCH /users/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = UserRoutes.routes[IO](store, testLogger)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/users").withEntity(
          CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */)
        )
      )
      created <- postResponse.as[UserResponse]
      _ <- testLogger.logged // drain POST's own log lines before the PATCH
      patchResponse <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/users" / created.id)
          .withEntity(UpdateUserRequest(/* codegen:fields:TEST_UPDATE_REQUEST_ARGS */))
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(patchResponse.status, Status.Ok)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("PATCH") &&
            m.ctx.get("user_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("user_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test("PATCH /users/{id} logs a WARN for an unknown id") {
    for {
      store <- UserStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = UserRoutes.routes[IO](store, testLogger)
      response <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/users" / "unknown-id")
          .withEntity(UpdateUserRequest(/* codegen:fields:TEST_UPDATE_REQUEST_ARGS */))
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("user_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test(
    "DELETE /users/{id} returns 204, and a subsequent GET returns 404"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/users").withEntity(
          CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */)
        )
      )
      created <- postResponse.as[UserResponse]
      deleteResponse <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/users" / created.id)
      )
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users" / created.id)
      )
    } yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      assertEquals(getResponse.status, Status.NotFound)
    }
  }

  test(
    "DELETE /users/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/users" / "unknown-id")
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "DELETE /users/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = UserRoutes.routes[IO](store, testLogger)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/users").withEntity(
          CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */)
        )
      )
      created <- postResponse.as[UserResponse]
      _ <- testLogger.logged // drain POST's own log lines before the DELETE
      deleteResponse <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/users" / created.id)
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("DELETE") &&
            m.ctx.get("user_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("user_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test("DELETE /users/{id} logs a WARN for an unknown id") {
    for {
      store <- UserStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = UserRoutes.routes[IO](store, testLogger)
      response <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/users" / "unknown-id")
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("user_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test("PUT /users/{id} returns 200 with the replaced entity") {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/users").withEntity(
          CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */)
        )
      )
      created <- postResponse.as[UserResponse]
      putResponse <- routes.orNotFound.run(
        Request[IO](Method.PUT, uri"/users" / created.id)
          .withEntity(UpdateUserRequest(/* codegen:fields:TEST_UPDATE_REQUEST_ARGS */))
      )
      replaced <- putResponse.as[UserResponse]
    } yield {
      assertEquals(putResponse.status, Status.Ok)
      assertEquals(replaced.id, created.id)
    }
  }

  test(
    "PUT /users/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.PUT, uri"/users" / "unknown-id")
          .withEntity(UpdateUserRequest(/* codegen:fields:TEST_UPDATE_REQUEST_ARGS */))
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "wrapped routes (with tracing middleware) record a span for a handled request"
  ) {
    Tracing.test[IO]("user-service-test").use { testTracer =>
      for {
        store <- UserStore.inMemory[IO]
        routes = ServerTracing.middleware(testTracer.tracer)(
          UserRoutes.routes[IO](store, NoOpLogger[IO])
        )
        request = Request[IO](Method.POST, uri"/users")
          .withEntity(CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */))
        response <- routes.orNotFound.run(request)
        spans <- testTracer.finishedSpans
      } yield {
        assertEquals(response.status, Status.Created)
        assertEquals(spans.map(_.getName), List("POST /users"))
      }
    }
  }

  test(
    "GET /users with no query params returns up to 20 entities newest-first with X-Total-Count"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      first <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/users").withEntity(
          CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */)
        )
      )
      firstEntity <- first.as[UserResponse]
      _ <- IO.sleep(2.millis)
      second <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/users").withEntity(
          CreateUserRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */)
        )
      )
      secondEntity <- second.as[UserResponse]
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users")
      )
      body <- response.as[List[UserResponse]]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body.map(_.id), List(secondEntity.id, firstEntity.id))
      assertEquals(
        response.headers.get(ci"X-Total-Count").map(_.head.value),
        Some("2")
      )
    }
  }

  test("GET /users defaults to a page of at most 20 entities") {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      _ <- List.fill(21)(store.create(/* codegen:fields:TEST_CREATE_ARGS */)).sequence
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users")
      )
      body <- response.as[List[UserResponse]]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body.size, 20)
      assertEquals(
        response.headers.get(ci"X-Total-Count").map(_.head.value),
        Some("21")
      )
    }
  }

  test("GET /users respects limit and offset") {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      _ <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
      _ <- IO.sleep(2.millis)
      second <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
      _ <- IO.sleep(2.millis)
      _ <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
      response <- routes.orNotFound.run(
        Request[IO](
          Method.GET,
          uri"/users".withQueryParam("limit", 1).withQueryParam("offset", 1)
        )
      )
      body <- response.as[List[UserResponse]]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body.map(_.id), List(second.id))
      assertEquals(
        response.headers.get(ci"X-Total-Count").map(_.head.value),
        Some("3")
      )
    }
  }

  test(
    "GET /users on an empty store returns 200 with [] and X-Total-Count: 0"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users")
      )
      body <- response.as[List[UserResponse]]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body, Nil)
      assertEquals(
        response.headers.get(ci"X-Total-Count").map(_.head.value),
        Some("0")
      )
    }
  }

  test(
    "GET /users with an offset past the end returns [] and the full total"
  ) {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      _ <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
      _ <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users".withQueryParam("offset", 5))
      )
      body <- response.as[List[UserResponse]]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body, Nil)
      assertEquals(
        response.headers.get(ci"X-Total-Count").map(_.head.value),
        Some("2")
      )
    }
  }

  test("GET /users with limit<=0 returns 400") {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users".withQueryParam("limit", 0))
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.BadRequest)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test("GET /users with limit>100 returns 400") {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users".withQueryParam("limit", 101))
      )
    } yield assertEquals(response.status, Status.BadRequest)
  }

  test("GET /users with offset<0 returns 400") {
    for {
      store <- UserStore.inMemory[IO]
      routes = UserRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/users".withQueryParam("offset", -1))
      )
    } yield assertEquals(response.status, Status.BadRequest)
  }
}
