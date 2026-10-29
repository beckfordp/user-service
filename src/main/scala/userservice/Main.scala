package userservice

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder

/** Acquires every dependency this service needs, delegates route assembly to
  * `AppRoutes.all`, and serves the result — a single flat `Resource`
  * for-comprehension, one `<-` per dependency. Every dependency that isn't
  * already a `Resource` is acquired via a `.resource`/`.loadValidated` method
  * on its own owning type (UserServiceConfig, Migrations, Telemetry), so none
  * of their internals (PureConfig, Flyway, OpenTelemetry) leak into this file.
  * The two exceptions: `EmberServerBuilder` is already `Resource`-returning
  * (http4s' own type, no wrapper needed), and the startup log line stays an
  * explicit `Resource.eval` here since logging isn't itself a dependency. Route
  * construction (`Docs.routes` OpenAPI/Swagger, `AsyncApiDocs.routes` raw
  * AsyncAPI spec, `fileService` static AsyncAPI HTML, the ordering those
  * require, and the tracing/metrics/CORS middleware chain) lives in
  * `AppRoutes`, not here. See `docs/writing-main.md` for the full rationale and
  * order-service for a fuller worked example once a service grows optional
  * dependencies.
  */
object Main extends IOApp.Simple {

  val run: IO[Unit] = (for {
    (config, port) <- Resource.eval(
      UserServiceConfig.loadValidated[IO]
    )
    _ <- Migrations.resource[IO](config.postgres)
    telemetry <- Telemetry.resource[IO](config.serviceName, config.metricsPort)
    _ <- Resource.eval(
      telemetry.logger.info(
        Map(
          "port" -> config.port.toString,
          "metrics_port" -> config.metricsPort.toString
        )
      )("user-service starting")
    )
    store <- UserStore.postgres[IO](config.postgres, telemetry.meter)
    routes = AppRoutes.all[IO](
      store,
      telemetry,
      config.auth,
      AsyncApiChannels.channels,
      config.asyncApiHtmlDir
    )
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port)
      .withHttpApp(routes.orNotFound)
      .build
  } yield ()).useForever
}
