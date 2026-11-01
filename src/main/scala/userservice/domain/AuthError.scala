package userservice.domain

/** Errors `POST /auth/signup` can return. */
sealed trait SignupError

/** Errors `POST /auth/login` can return. */
sealed trait LoginError

/** Every nameable auth failure, each tagged with the endpoint(s) that can
  * return it, so an endpoint's error type lists only what it can produce. The
  * wire message for each lives at the HTTP edge (`AuthRoutes`).
  */
object AuthError {
  case object InvalidEmail extends SignupError with LoginError
  case object PasswordTooShort extends SignupError
  case object PasswordMissing extends LoginError
  case object DuplicateEmail extends SignupError
  case object InvalidCredentials extends LoginError
}
