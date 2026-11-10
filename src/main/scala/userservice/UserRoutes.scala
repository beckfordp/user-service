// SPDX-License-Identifier: MIT

package userservice

import cats.Monad
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.Schema.annotations.{description, encodedExample}
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint
import userservice.domain.{AccountId, Email}

import java.time.Instant
import java.util.UUID

@description("Public identity for one account.")
final case class UserResponse(
    @description("User id (UUID).")
    @encodedExample("5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e")
    userId: String,
    @description("Normalized email address stored at signup.")
    @encodedExample("user@example.com")
    email: String,
    @description("When the account was created (ISO-8601 instant).")
    @encodedExample("2026-10-28T12:00:00Z")
    createdAt: Instant
)

object UserResponse {
  implicit val codec: Codec[UserResponse] = deriveCodec
}

/** Public identity read (ADM-3/ADR 0025): `GET /users/{id}` for
  * notification-service's customerId → email resolution. Unauthenticated
  * local/demo read, like the other services' read APIs; publishes nothing.
  */
object UserRoutes {

  private val tag = "users"

  private val exampleUser = UserResponse(
    userId = "5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e",
    email = "user@example.com",
    createdAt = Instant.parse("2026-10-28T12:00:00Z")
  )

  private val idPath: EndpointInput.PathCapture[AccountId] =
    path[AccountId]("id")
      .description("User id (UUID). A malformed id returns 400.")
      .example(
        AccountId.fromUuid(
          UUID.fromString("5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e")
        )
      )

  private val notFoundVariant: EndpointOutput.OneOfVariant[ErrorResponse] =
    oneOfVariant(
      statusCode(StatusCode.NotFound)
        .and(
          jsonBody[ErrorResponse]
            .description("No account has this id.")
            .example(ErrorResponse("User not found"))
        )
    )

  private val getUserEndpoint
      : PublicEndpoint[AccountId, ErrorResponse, UserResponse, Any] =
    endpoint.get
      .in("users" / idPath)
      .out(jsonBody[UserResponse].example(exampleUser))
      .errorOut(oneOf[ErrorResponse](notFoundVariant))
      .summary("Get a user")
      .description(
        "Returns the public identity (id, email, createdAt) for one account. An unknown id returns 404; a malformed id returns 400. Unauthenticated local/demo read."
      )
      .tag(tag)

  def getUserServerEndpoint[F[_]: Monad](
      store: UserStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    getUserEndpoint.serverLogic[F] { id =>
      val renderedId = AccountId.render(id)
      val context =
        Map(
          "method" -> "GET",
          "path" -> s"/users/$renderedId",
          "user_id" -> renderedId
        )
      for {
        _ <- logger.info(context)("Received request")
        result <- store.findById(id).flatMap {
          case Some(account) =>
            logger
              .info(context)("Request completed")
              .as(
                Right[ErrorResponse, UserResponse](
                  UserResponse(
                    AccountId.render(account.id),
                    Email.render(account.email),
                    account.createdAt
                  )
                )
              )
          case None =>
            logger
              .warn(context)("User not found")
              .as(
                Left[ErrorResponse, UserResponse](
                  ErrorResponse("User not found")
                )
              )
        }
      } yield result
    }
}
