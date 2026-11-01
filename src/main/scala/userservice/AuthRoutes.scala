package userservice

import cats.Monad
import cats.effect.{Async, Clock}
import cats.effect.std.UUIDGen
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.http4s.HttpRoutes
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import userservice.domain.{
  AccountId,
  AuthError,
  Credentials,
  LoginError,
  Password,
  SignupError,
  TokenId,
  TokenKind
}

import scala.reflect.ClassTag

final case class SignupRequest(email: String, password: String)

object SignupRequest {
  implicit val codec: Codec[SignupRequest] = deriveCodec
}

final case class LoginRequest(email: String, password: String)

object LoginRequest {
  implicit val codec: Codec[LoginRequest] = deriveCodec
}

final case class SignupResponse(userId: String, token: String)

object SignupResponse {
  implicit val codec: Codec[SignupResponse] = deriveCodec
}

final case class LoginResponse(
    userId: String,
    token: String,
    refreshToken: String
)

object LoginResponse {
  implicit val codec: Codec[LoginResponse] = deriveCodec
}

final case class ErrorResponse(error: String)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

/** Public identity API (US-16.1/16.2) — contracts in
  * `gluon/docs/system-design.md`. Signup also writes the `UserRegistered`
  * outbox row (US-16.3); login publishes nothing. Raw input is parsed once by
  * `domain.Credentials`; each endpoint's error output lists only the
  * [[SignupError]]/[[LoginError]] cases it can return.
  */
object AuthRoutes {

  /** The wire message for each error — the response bodies' only field. */
  private[userservice] def message(error: SignupError | LoginError): String =
    error match {
      case AuthError.InvalidEmail     => "Enter a valid email address"
      case AuthError.PasswordTooShort =>
        s"Password must be at least ${Password.MinLength} characters"
      case AuthError.PasswordMissing => "Enter your password"
      case AuthError.DuplicateEmail  =>
        "An account with this email already exists"
      case AuthError.InvalidCredentials => "Email or password is incorrect"
    }

  private def fixedVariant[E <: SignupError | LoginError: ClassTag](
      status: StatusCode,
      error: E
  ): EndpointOutput.OneOfVariant[E] =
    oneOfVariant(
      statusCode(status)
        .and(jsonBody[ErrorResponse])
        .map[E](_ => error)(e => ErrorResponse(message(e)))
    )

  private val invalidEmailVariant =
    fixedVariant(StatusCode.BadRequest, AuthError.InvalidEmail)

  private val passwordTooShortVariant =
    fixedVariant(StatusCode.BadRequest, AuthError.PasswordTooShort)

  private val passwordMissingVariant =
    fixedVariant(StatusCode.BadRequest, AuthError.PasswordMissing)

  private val duplicateVariant =
    fixedVariant(StatusCode.Conflict, AuthError.DuplicateEmail)

  private val invalidCredentialsVariant =
    fixedVariant(StatusCode.Unauthorized, AuthError.InvalidCredentials)

  private val signupEndpoint: PublicEndpoint[
    SignupRequest,
    SignupError,
    SignupResponse,
    Any
  ] =
    endpoint.post
      .in("auth" / "signup")
      .in(jsonBody[SignupRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[SignupResponse])
      .errorOut(
        oneOf[SignupError](
          invalidEmailVariant,
          passwordTooShortVariant,
          duplicateVariant
        )
      )

  private val loginEndpoint: PublicEndpoint[
    LoginRequest,
    LoginError,
    LoginResponse,
    Any
  ] =
    endpoint.post
      .in("auth" / "login")
      .in(jsonBody[LoginRequest])
      .out(jsonBody[LoginResponse])
      .errorOut(
        oneOf[LoginError](
          invalidEmailVariant,
          passwordMissingVariant,
          invalidCredentialsVariant
        )
      )

  def signupServerEndpoint[F[_]: Monad: Clock: UUIDGen](
      store: UserStore[F],
      hasher: PasswordHasher[F],
      tokens: TokenIssuer[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    signupEndpoint.serverLogic[F] { request =>
      val context = Map("method" -> "POST", "path" -> "/auth/signup")
      for {
        _ <- logger.info(context)("Received request")
        result <- Credentials.parseSignup(
          request.email,
          request.password
        ) match {
          case Left(error) =>
            logger
              .warn(context + ("error" -> message(error)))(
                "Invalid signup request"
              )
              .as(Left(error))
          case Right(credentials) =>
            for {
              userId <- UUIDGen.randomUUID[F].map(AccountId.fromUuid)
              accessTokenId <- UUIDGen.randomUUID[F].map(TokenId.fromUuid)
              passwordHash <- hasher.hash(credentials.password)
              accessToken <- tokens.issue(TokenKind.Access)
              envelope <- AuthEvents.userRegistered[F](
                userId,
                credentials.email
              )
              created <- store.createAccount(
                userId,
                credentials.email,
                passwordHash,
                NewToken(
                  accessTokenId,
                  userId,
                  accessToken.hash,
                  TokenKind.Access,
                  accessToken.expiresAt
                ),
                envelope
              )
              response <- created match {
                case Left(DuplicateEmail) =>
                  logger
                    .warn(context)("Duplicate signup")
                    .as(
                      Left[SignupError, SignupResponse](
                        AuthError.DuplicateEmail
                      )
                    )
                case Right(account) =>
                  logger
                    .info(
                      context + ("user_id" -> AccountId.render(account.id))
                    )(
                      "Account created"
                    )
                    .as(
                      Right[SignupError, SignupResponse](
                        SignupResponse(
                          AccountId.render(account.id),
                          accessToken.value
                        )
                      )
                    )
              }
            } yield response
        }
      } yield result
    }

  def loginServerEndpoint[F[_]: Monad: UUIDGen](
      store: UserStore[F],
      hasher: PasswordHasher[F],
      tokens: TokenIssuer[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    loginEndpoint.serverLogic[F] { request =>
      val context = Map("method" -> "POST", "path" -> "/auth/login")
      for {
        _ <- logger.info(context)("Received request")
        result <- Credentials.parseLogin(
          request.email,
          request.password
        ) match {
          case Left(error) =>
            logger
              .warn(context + ("error" -> message(error)))(
                "Invalid login request"
              )
              .as(Left(error))
          case Right(credentials) =>
            store.findByEmail(credentials.email).flatMap {
              case None =>
                logger
                  .warn(context)("Login failed")
                  .as(
                    Left[LoginError, LoginResponse](
                      AuthError.InvalidCredentials
                    )
                  )
              case Some(account) =>
                hasher
                  .verify(credentials.password, account.passwordHash)
                  .flatMap {
                    case false =>
                      logger
                        .warn(context)("Login failed")
                        .as(
                          Left[LoginError, LoginResponse](
                            AuthError.InvalidCredentials
                          )
                        )
                    case true =>
                      for {
                        accessId <- UUIDGen.randomUUID[F].map(TokenId.fromUuid)
                        refreshId <- UUIDGen.randomUUID[F].map(TokenId.fromUuid)
                        access <- tokens.issue(TokenKind.Access)
                        refresh <- tokens.issue(TokenKind.Refresh)
                        _ <- store.recordLoginTokens(
                          NewToken(
                            accessId,
                            account.id,
                            access.hash,
                            TokenKind.Access,
                            access.expiresAt
                          ),
                          NewToken(
                            refreshId,
                            account.id,
                            refresh.hash,
                            TokenKind.Refresh,
                            refresh.expiresAt
                          )
                        )
                        _ <- logger.info(
                          context + ("user_id" -> AccountId.render(account.id))
                        )(
                          "Login succeeded"
                        )
                      } yield Right(
                        LoginResponse(
                          AccountId.render(account.id),
                          access.value,
                          refresh.value
                        )
                      )
                  }
            }
        }
      } yield result
    }

  def routes[F[_]: Async](
      store: UserStore[F],
      hasher: PasswordHasher[F],
      tokens: TokenIssuer[F],
      logger: StructuredLogger[F]
  ): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        signupServerEndpoint[F](store, hasher, tokens, logger),
        loginServerEndpoint[F](store, hasher, tokens, logger)
      )
    )
}
