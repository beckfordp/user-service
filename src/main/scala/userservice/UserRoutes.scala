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

final case class CreateUserRequest( /* codegen:fields:CREATE_PARAMS */ )

object CreateUserRequest {
  implicit val codec: Codec[CreateUserRequest] = deriveCodec
}

final case class UpdateUserRequest( /* codegen:fields:UPDATE_PARAMS */ )

object UpdateUserRequest {
  implicit val codec: Codec[UpdateUserRequest] = deriveCodec
}

final case class UserResponse(
    id: String,
    // codegen:fields:CASE_CLASS_FIELD
    createdAt: java.time.Instant,
    updatedAt: java.time.Instant
)

object UserResponse {
  implicit val codec: Codec[UserResponse] = deriveCodec

  def apply(entity: User): UserResponse =
    UserResponse(
      entity.id,
      /* codegen:fields:RESPONSE_APPLY_ARGS */
      entity.createdAt,
      entity.updatedAt
    )
}

final case class ErrorResponse(error: String)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

object UserRoutes {

  private val createUserEndpoint: PublicEndpoint[
    CreateUserRequest,
    Unit,
    UserResponse,
    Any
  ] =
    endpoint.post
      .in("users")
      .in(jsonBody[CreateUserRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[UserResponse])

  private val notFoundOutput: EndpointOutput[UserError] =
    statusCode(StatusCode.NotFound)
      .and(jsonBody[ErrorResponse])
      .map[UserError](_ => UserNotFound)(_ => ErrorResponse("User not found"))

  private val invalidPaginationOutput: EndpointOutput[UserError] =
    statusCode(StatusCode.BadRequest)
      .and(jsonBody[ErrorResponse])
      .map[UserError](_ => InvalidPagination)(_ =>
        ErrorResponse("Invalid pagination parameters")
      )

  private val listUsersEndpoint: PublicEndpoint[
    (Option[Int], Option[Int]),
    UserError,
    (List[UserResponse], String),
    Any
  ] =
    endpoint.get
      .in("users")
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Int]]("offset"))
      .out(jsonBody[List[UserResponse]])
      .out(header[String]("X-Total-Count"))
      .errorOut(invalidPaginationOutput)

  private val getUserEndpoint: PublicEndpoint[
    String,
    UserError,
    UserResponse,
    Any
  ] =
    endpoint.get
      .in("users" / path[String]("id"))
      .out(jsonBody[UserResponse])
      .errorOut(notFoundOutput)

  private val updateUserEndpoint: PublicEndpoint[
    (String, UpdateUserRequest),
    UserError,
    UserResponse,
    Any
  ] =
    endpoint.patch
      .in("users" / path[String]("id"))
      .in(jsonBody[UpdateUserRequest])
      .out(jsonBody[UserResponse])
      .errorOut(notFoundOutput)

  private val replaceUserEndpoint: PublicEndpoint[
    (String, UpdateUserRequest),
    UserError,
    UserResponse,
    Any
  ] =
    endpoint.put
      .in("users" / path[String]("id"))
      .in(jsonBody[UpdateUserRequest])
      .out(jsonBody[UserResponse])
      .errorOut(notFoundOutput)

  private val deleteUserEndpoint: PublicEndpoint[String, UserError, Unit, Any] =
    endpoint.delete
      .in("users" / path[String]("id"))
      .out(statusCode(StatusCode.NoContent))
      .errorOut(notFoundOutput)

  def serverEndpoint[F[_]: Async](
      store: UserStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    createUserEndpoint.serverLogicSuccess[F] { req =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "POST",
            "path" -> "/users"
          )
        )("Received request")
        entity <- store
          .create( /* codegen:fields:CREATE_CALL_ARGS */ )
          .onError { case error =>
            logger.error(Map.empty, error)("Persisting the user failed")
          }
        _ <- logger.info(
          Map("user_id" -> entity.id)
        )("Request completed")
      } yield UserResponse(entity)
    }

  def getUserServerEndpoint[F[_]: Async](
      store: UserStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    getUserEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map("method" -> "GET", "path" -> s"/users/$id", "user_id" -> id)
        )(
          "Received request"
        )
        result <- store.get(id).flatMap {
          case Some(entity) =>
            logger
              .info(Map("user_id" -> id))("Request completed")
              .as(Right(UserResponse(entity)))
          case None =>
            logger
              .warn(Map("user_id" -> id))("User not found")
              .as(Left(UserNotFound))
        }
      } yield result
    }

  /** Shared handler for `PATCH` (partial update) and `PUT` (full replace) —
    * both call `UserStore.update` with the same required update body; only the
    * logged HTTP method differs.
    */
  private def updateLogic[F[_]: Async](
      store: UserStore[F],
      logger: StructuredLogger[F],
      httpMethod: String
  )(
      id: String,
      req: UpdateUserRequest
  ): F[Either[UserError, UserResponse]] =
    for {
      _ <- logger.info(
        Map(
          "method" -> httpMethod,
          "path" -> s"/users/$id",
          "user_id" -> id
        )
      )("Received request")
      result <- store
        .update(id /* codegen:fields:UPDATE_CALL_ARGS */ )
        .flatMap {
          case Some(entity) =>
            logger
              .info(Map("user_id" -> id))("Request completed")
              .as(Right(UserResponse(entity)))
          case None =>
            logger
              .warn(Map("user_id" -> id))("User not found")
              .as(Left(UserNotFound))
        }
    } yield result

  def updateUserServerEndpoint[F[_]: Async](
      store: UserStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    updateUserEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PATCH")(id, req)
    }

  def replaceUserServerEndpoint[F[_]: Async](
      store: UserStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    replaceUserEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PUT")(id, req)
    }

  def deleteUserServerEndpoint[F[_]: Async](
      store: UserStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    deleteUserEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map("method" -> "DELETE", "path" -> s"/users/$id", "user_id" -> id)
        )("Received request")
        result <- store.delete(id).flatMap {
          case true =>
            logger
              .info(Map("user_id" -> id))("Request completed")
              .as(Right(()))
          case false =>
            logger
              .warn(Map("user_id" -> id))("User not found")
              .as(Left(UserNotFound))
        }
      } yield result
    }

  private val defaultLimit = 20
  private val maxLimit = 100

  def listUsersServerEndpoint[F[_]: Async](
      store: UserStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    listUsersEndpoint.serverLogic[F] { case (limitParam, offsetParam) =>
      val limit = limitParam.getOrElse(defaultLimit)
      val offset = offsetParam.getOrElse(0)
      for {
        _ <- logger.info(
          Map("method" -> "GET", "path" -> "/users")
        )("Received request")
        result <-
          if (limit <= 0 || limit > maxLimit || offset < 0) {
            logger
              .warn(
                Map("limit" -> limit.toString, "offset" -> offset.toString)
              )("Invalid pagination parameters")
              .as(Left(InvalidPagination))
          } else {
            store.list(limit, offset).flatMap { case (entities, total) =>
              logger
                .info(
                  Map(
                    "limit" -> limit.toString,
                    "offset" -> offset.toString,
                    "count" -> entities.size.toString,
                    "total" -> total.toString
                  )
                )("Request completed")
                .as(
                  Right(
                    (
                      entities.map(UserResponse.apply),
                      total.toString
                    )
                  )
                )
            }
          }
      } yield result
    }

  def routes[F[_]: Async](
      store: UserStore[F],
      logger: StructuredLogger[F]
  ): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        serverEndpoint(store, logger),
        getUserServerEndpoint(store, logger),
        updateUserServerEndpoint(store, logger),
        replaceUserServerEndpoint(store, logger),
        deleteUserServerEndpoint(store, logger),
        listUsersServerEndpoint(store, logger)
      )
    )
}
