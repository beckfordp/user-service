package userservice

import cats.effect.IO
import munit.CatsEffectSuite
import pureconfig.ConfigSource

import scala.concurrent.duration._

class UserServiceConfigSuite extends CatsEffectSuite {

  private val validHocon =
    """
      |port = 8080
      |metrics-port = 9090
      |service-name = "user-service"
      |async-api-html-dir = "target/asyncapi-docs"
      |postgres {
      |  host = "localhost"
      |  port = 5432
      |  database = "user"
      |  user = "user"
      |  password = "user"
      |}
      |auth {
      |  access-token-ttl = 1h
      |  refresh-token-ttl = 30d
      |  iterations = 210000
      |}
      |""".stripMargin

  test("loads a fully-specified config") {
    val result =
      ConfigSource.string(validHocon).load[UserServiceConfig]
    assertEquals(
      result,
      Right(
        UserServiceConfig(
          port = 8080,
          metricsPort = 9090,
          serviceName = "user-service",
          asyncApiHtmlDir = "target/asyncapi-docs",
          postgres = PostgresConfig(
            host = "localhost",
            port = 5432,
            database = "user",
            user = "user",
            password = "user"
          ),
          auth = AuthConfig(
            accessTokenTtl = 1.hour,
            refreshTokenTtl = 30.days,
            iterations = 210000
          )
        )
      )
    )
  }

  test("fails to load when a required field is missing") {
    val missingPassword =
      """
        |port = 8080
        |metrics-port = 9090
        |postgres {
        |  host = "localhost"
        |  port = 5432
        |  database = "user"
        |  user = "user"
        |}
        |""".stripMargin

    assert(
      ConfigSource
        .string(missingPassword)
        .load[UserServiceConfig]
        .isLeft
    )
  }

  test("load[F] reads the shipped application.conf defaults") {
    UserServiceConfig.load[IO].map { config =>
      assertEquals(config.port, 8080)
      assertEquals(config.metricsPort, 9090)
      assertEquals(config.serviceName, "user-service")
      assertEquals(config.asyncApiHtmlDir, "target/asyncapi-docs")
      assertEquals(
        config.postgres,
        PostgresConfig(
          "localhost",
          5432,
          "user",
          "user",
          "user"
        )
      )
      assertEquals(
        config.auth,
        AuthConfig(
          accessTokenTtl = 1.hour,
          refreshTokenTtl = 30.days,
          iterations = 210000
        )
      )
    }
  }
}
