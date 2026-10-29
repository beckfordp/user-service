package userservice

import cats.effect.Sync
import cats.syntax.all._
import com.comcast.ip4s.Port
import pureconfig.{ConfigReader, ConfigSource}

import scala.concurrent.duration.FiniteDuration

final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String
) derives ConfigReader

final case class AuthConfig(
    accessTokenTtl: FiniteDuration,
    refreshTokenTtl: FiniteDuration,
    iterations: Int
) derives ConfigReader

final case class UserServiceConfig(
    port: Int,
    metricsPort: Int,
    serviceName: String,
    asyncApiHtmlDir: String,
    postgres: PostgresConfig,
    auth: AuthConfig
) derives ConfigReader

object UserServiceConfig {
  def load[F[_]: Sync]: F[UserServiceConfig] =
    Sync[F].delay(ConfigSource.default.loadOrThrow[UserServiceConfig])

  /** `load`, plus parsing/validating `port` as an `ip4s.Port` - so `Main`
    * doesn't have to do that validation itself.
    */
  def loadValidated[F[_]: Sync]: F[(UserServiceConfig, Port)] =
    for {
      config <- load[F]
      port <- Sync[F].fromOption(
        Port.fromInt(config.port),
        new IllegalArgumentException(
          s"Invalid user-service port: ${config.port}"
        )
      )
    } yield (config, port)
}
