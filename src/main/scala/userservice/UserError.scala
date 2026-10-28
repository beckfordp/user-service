package userservice

sealed trait UserError

case object UserNotFound extends UserError

case object InvalidPagination extends UserError
