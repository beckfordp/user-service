// SPDX-License-Identifier: MIT

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
import sttp.tapir.Schema.annotations.{description, encodedExample}
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

@description("Credentials for a new account.")
final case class SignupRequest(
    @description("Email address; trimmed and lower-cased before storage.")
    @encodedExample("user@example.com")
    email: String,
    @description(
      "Plaintext password, at least 8 characters. Never stored or logged."
    )
    @encodedExample("correct-horse-battery-staple")
    password: String
)

object SignupRequest {
  implicit val codec: Codec[SignupRequest] = deriveCodec
}

@description("Credentials for an existing account.")
final case class LoginRequest(
    @description("Email address; trimmed and lower-cased before lookup.")
    @encodedExample("user@example.com")
    email: String,
    @description(
      "Plaintext password; must be non-empty. Never stored or logged."
    )
    @encodedExample("correct-horse-battery-staple")
    password: String
)

object LoginRequest {
  implicit val codec: Codec[LoginRequest] = deriveCodec
}

@description("A new account and its first access token.")
final case class SignupResponse(
    @description("New user's id (UUID).")
    @encodedExample("5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e")
    userId: String,
    @description(
      "Opaque access token (1-hour TTL). Shown once; store it client-side, never log it."
    )
    @encodedExample("example-access-token-not-valid")
    token: String
)

object SignupResponse {
  implicit val codec: Codec[SignupResponse] = deriveCodec
}

@description("A successful login and a fresh token pair.")
final case class LoginResponse(
    @description("User's id (UUID).")
    @encodedExample("5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e")
    userId: String,
    @description("Opaque access token (1-hour TTL). Shown once; never log it.")
    @encodedExample("example-access-token-not-valid")
    token: String,
    @description(
      "Opaque refresh token (30-day TTL). Only its hash is stored; never log it."
    )
    @encodedExample("example-refresh-token-not-valid")
    refreshToken: String
)

object LoginResponse {
  implicit val codec: Codec[LoginResponse] = deriveCodec
}

@description("Error body.")
final case class ErrorResponse(
    @description("Human-readable reason.")
    @encodedExample("Enter a valid email address")
    error: String
)

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
      error: E,
      what: String
  ): EndpointOutput.OneOfVariant[E] =
    oneOfVariant(
      statusCode(status)
        .and(
          jsonBody[ErrorResponse]
            .description(what)
            .example(ErrorResponse(message(error)))
        )
        .map[E](_ => error)(e => ErrorResponse(message(e)))
    )

  private val invalidEmailVariant =
    fixedVariant(
      StatusCode.BadRequest,
      AuthError.InvalidEmail,
      "Email is invalid."
    )

  private val passwordTooShortVariant =
    fixedVariant(
      StatusCode.BadRequest,
      AuthError.PasswordTooShort,
      "Password is shorter than 8 characters."
    )

  private val passwordMissingVariant =
    fixedVariant(
      StatusCode.BadRequest,
      AuthError.PasswordMissing,
      "Password is empty."
    )

  private val duplicateVariant =
    fixedVariant(
      StatusCode.Conflict,
      AuthError.DuplicateEmail,
      "Email is already registered."
    )

  private val invalidCredentialsVariant =
    fixedVariant(
      StatusCode.Unauthorized,
      AuthError.InvalidCredentials,
      "Unknown email or wrong password."
    )

  private val exampleSignupRequest =
    SignupRequest("user@example.com", "correct-horse-battery-staple")

  private val exampleSignupResponse = SignupResponse(
    "5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e",
    "example-access-token-not-valid"
  )

  private val exampleLoginRequest =
    LoginRequest("user@example.com", "correct-horse-battery-staple")

  private val exampleLoginResponse = LoginResponse(
    "5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e",
    "example-access-token-not-valid",
    "example-refresh-token-not-valid"
  )

  private val signupEndpoint: PublicEndpoint[
    SignupRequest,
    SignupError,
    SignupResponse,
    Any
  ] =
    endpoint.post
      .in("auth" / "signup")
      .in(jsonBody[SignupRequest].example(exampleSignupRequest))
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[SignupResponse].example(exampleSignupResponse))
      .errorOut(
        oneOf[SignupError](
          invalidEmailVariant,
          passwordTooShortVariant,
          duplicateVariant
        )
      )
      .summary("Sign up")
      .description(
        "Creates an account and returns its first access token (201). The email is trimmed and lower-cased; an invalid email or a password under 8 characters → 400, an already-registered email → 409. Writes the account, token and `UserRegistered` outbox row in one transaction (event published asynchronously). Not idempotent — a retry with the same email → 409."
      )
      .tag("auth")

  private val loginEndpoint: PublicEndpoint[
    LoginRequest,
    LoginError,
    LoginResponse,
    Any
  ] =
    endpoint.post
      .in("auth" / "login")
      .in(jsonBody[LoginRequest].example(exampleLoginRequest))
      .out(jsonBody[LoginResponse].example(exampleLoginResponse))
      .errorOut(
        oneOf[LoginError](
          invalidEmailVariant,
          passwordMissingVariant,
          invalidCredentialsVariant
        )
      )
      .summary("Log in")
      .description(
        "Checks email + password and returns a fresh access token and refresh token (200). An invalid email or empty password → 400. An unknown email and a wrong password both → 401 with the same body; an unknown email still runs one password hash so timing doesn't reveal registered emails. Tokens are opaque; only their SHA-256 hashes are stored."
      )
      .tag("auth")

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
      val loginFailed: F[Either[LoginError, LoginResponse]] =
        logger
          .warn(context)("Login failed")
          .as(Left(AuthError.InvalidCredentials))
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
            for {
              account <- store.findByEmail(credentials.email)
              check <- hasher.verify(
                credentials.password,
                account.map(_.passwordHash)
              )
              response <- account match {
                // verify answered Mismatch after a dummy check (no oracle).
                case None          => loginFailed
                case Some(account) =>
                  check match {
                    case PasswordCheck.Mismatch  => loginFailed
                    case PasswordCheck.Malformed =>
                      logger
                        .error(
                          context + ("user_id" -> AccountId.render(account.id))
                        )("Stored password hash is malformed")
                        .as(
                          Left[LoginError, LoginResponse](
                            AuthError.InvalidCredentials
                          )
                        )
                    case PasswordCheck.Match =>
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
            } yield response
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
