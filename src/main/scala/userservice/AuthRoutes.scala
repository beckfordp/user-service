package userservice

import cats.effect.Async
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

import java.util.UUID

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

final case class LoginResponse(token: String, refreshToken: String)

object LoginResponse {
  implicit val codec: Codec[LoginResponse] = deriveCodec
}

final case class ErrorResponse(error: String)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

sealed trait AuthError

object AuthError {
  final case class Invalid(error: String) extends AuthError
  case object DuplicateEmail extends AuthError
  case object InvalidCredentials extends AuthError
}

/** Public identity API (US-16.1/16.2) — contracts in
  * `gluon/docs/system-design.md`. Signup also writes the `UserRegistered`
  * outbox row (US-16.3); login publishes nothing.
  */
object AuthRoutes {

  private val invalidVariant: EndpointOutput.OneOfVariant[AuthError.Invalid] =
    oneOfVariant(
      statusCode(StatusCode.BadRequest)
        .and(jsonBody[ErrorResponse])
        .map[AuthError.Invalid](e => AuthError.Invalid(e.error))(e =>
          ErrorResponse(e.error)
        )
    )

  private val duplicateVariant
      : EndpointOutput.OneOfVariant[AuthError.DuplicateEmail.type] =
    oneOfVariant(
      statusCode(StatusCode.Conflict)
        .and(jsonBody[ErrorResponse])
        .map[AuthError.DuplicateEmail.type](_ => AuthError.DuplicateEmail)(_ =>
          ErrorResponse("An account with this email already exists")
        )
    )

  private val invalidCredentialsVariant
      : EndpointOutput.OneOfVariant[AuthError.InvalidCredentials.type] =
    oneOfVariant(
      statusCode(StatusCode.Unauthorized)
        .and(jsonBody[ErrorResponse])
        .map[AuthError.InvalidCredentials.type](_ =>
          AuthError.InvalidCredentials
        )(_ => ErrorResponse("Email or password is incorrect"))
    )

  private val signupEndpoint: PublicEndpoint[
    SignupRequest,
    AuthError,
    SignupResponse,
    Any
  ] =
    endpoint.post
      .in("auth" / "signup")
      .in(jsonBody[SignupRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[SignupResponse])
      .errorOut(oneOf[AuthError](invalidVariant, duplicateVariant))

  private val loginEndpoint: PublicEndpoint[
    LoginRequest,
    AuthError,
    LoginResponse,
    Any
  ] =
    endpoint.post
      .in("auth" / "login")
      .in(jsonBody[LoginRequest])
      .out(jsonBody[LoginResponse])
      .errorOut(
        oneOf[AuthError](invalidVariant, invalidCredentialsVariant)
      )

  private val emailPattern = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$".r

  private def normalizeEmail(email: String): String =
    email.trim.toLowerCase

  private def validateSignup(
      request: SignupRequest
  ): Either[AuthError, String] = {
    val email = normalizeEmail(request.email)
    if (emailPattern.findFirstIn(email).isEmpty)
      Left(AuthError.Invalid("Enter a valid email address"))
    else if (request.password.length < 8)
      Left(AuthError.Invalid("Password must be at least 8 characters"))
    else Right(email)
  }

  private def validateLogin(
      request: LoginRequest
  ): Either[AuthError, String] = {
    val email = normalizeEmail(request.email)
    if (emailPattern.findFirstIn(email).isEmpty)
      Left(AuthError.Invalid("Enter a valid email address"))
    else if (request.password.isEmpty)
      Left(AuthError.Invalid("Enter your password"))
    else Right(email)
  }

  def signupServerEndpoint[F[_]: Async](
      store: UserStore[F],
      hasher: PasswordHasher,
      tokens: TokenIssuer,
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    signupEndpoint.serverLogic[F] { request =>
      val context = Map("method" -> "POST", "path" -> "/auth/signup")
      for {
        _ <- logger.info(context)("Received request")
        result <- validateSignup(request) match {
          case Left(error) =>
            logger
              .warn(context + ("error" -> errorText(error)))(
                "Invalid signup request"
              )
              .as(Left(error))
          case Right(email) =>
            for {
              userId <- Async[F].delay(UUID.randomUUID().toString)
              passwordHash <- hasher.hash[F](request.password)
              accessToken <- tokens.issue[F](TokenKind.Access)
              envelope <- AuthEvents.userRegistered[F](userId, email)
              created <- store.createAccount(
                userId,
                email,
                passwordHash,
                NewToken(
                  UUID.randomUUID().toString,
                  userId,
                  accessToken.hash,
                  TokenKind.Access.wire,
                  accessToken.expiresAt
                ),
                envelope
              )
              response <- created match {
                case Left(DuplicateEmail) =>
                  logger
                    .warn(context)("Duplicate signup")
                    .as(
                      Left[AuthError, SignupResponse](
                        AuthError.DuplicateEmail
                      )
                    )
                case Right(account) =>
                  logger
                    .info(context + ("user_id" -> account.id))(
                      "Account created"
                    )
                    .as(
                      Right[AuthError, SignupResponse](
                        SignupResponse(account.id, accessToken.value)
                      )
                    )
              }
            } yield response
        }
      } yield result
    }

  def loginServerEndpoint[F[_]: Async](
      store: UserStore[F],
      hasher: PasswordHasher,
      tokens: TokenIssuer,
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    loginEndpoint.serverLogic[F] { request =>
      val context = Map("method" -> "POST", "path" -> "/auth/login")
      for {
        _ <- logger.info(context)("Received request")
        result <- validateLogin(request) match {
          case Left(error) =>
            logger
              .warn(context + ("error" -> errorText(error)))(
                "Invalid login request"
              )
              .as(Left(error))
          case Right(email) =>
            store.findByEmail(email).flatMap {
              case None =>
                logger
                  .warn(context)("Login failed")
                  .as(
                    Left[AuthError, LoginResponse](
                      AuthError.InvalidCredentials
                    )
                  )
              case Some(account) =>
                hasher
                  .verify[F](request.password, account.passwordHash)
                  .flatMap {
                    case false =>
                      logger
                        .warn(context)("Login failed")
                        .as(
                          Left[AuthError, LoginResponse](
                            AuthError.InvalidCredentials
                          )
                        )
                    case true =>
                      for {
                        access <- tokens.issue[F](TokenKind.Access)
                        refresh <- tokens.issue[F](TokenKind.Refresh)
                        _ <- store.recordLoginTokens(
                          NewToken(
                            UUID.randomUUID().toString,
                            account.id,
                            access.hash,
                            TokenKind.Access.wire,
                            access.expiresAt
                          ),
                          NewToken(
                            UUID.randomUUID().toString,
                            account.id,
                            refresh.hash,
                            TokenKind.Refresh.wire,
                            refresh.expiresAt
                          )
                        )
                        _ <- logger.info(context + ("user_id" -> account.id))(
                          "Login succeeded"
                        )
                      } yield Right(LoginResponse(access.value, refresh.value))
                  }
            }
        }
      } yield result
    }

  private def errorText(error: AuthError): String = error match {
    case AuthError.Invalid(message)   => message
    case AuthError.DuplicateEmail     => "duplicate email"
    case AuthError.InvalidCredentials => "invalid credentials"
  }

  def routes[F[_]: Async](
      store: UserStore[F],
      hasher: PasswordHasher,
      tokens: TokenIssuer,
      logger: StructuredLogger[F]
  ): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        signupServerEndpoint[F](store, hasher, tokens, logger),
        loginServerEndpoint[F](store, hasher, tokens, logger)
      )
    )
}
