// SPDX-License-Identifier: MIT

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
    load[F].flatMap(config => Sync[F].fromEither(validate(config)))

  /** Startup checks PureConfig's types can't express: a bindable port, a usable
    * PBKDF2 iteration count (0 or less would fail every signup at runtime) and
    * positive token TTLs.
    */
  def validate(
      config: UserServiceConfig
  ): Either[IllegalArgumentException, (UserServiceConfig, Port)] =
    for {
      port <- Port
        .fromInt(config.port)
        .toRight(
          new IllegalArgumentException(
            s"Invalid user-service port: ${config.port}"
          )
        )
      _ <- Either.cond(
        config.auth.iterations >= 1,
        (),
        new IllegalArgumentException(
          s"auth.iterations must be at least 1, got ${config.auth.iterations}"
        )
      )
      _ <- Either.cond(
        config.auth.accessTokenTtl.toSeconds > 0 &&
          config.auth.refreshTokenTtl.toSeconds > 0,
        (),
        new IllegalArgumentException("auth token TTLs must be positive")
      )
    } yield (config, port)
}
