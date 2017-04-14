package ca.schwitzer.scaladon

import akka.http.scaladsl.model.StatusCode

sealed trait MastodonResponse[+A]
sealed trait MastodonError extends MastodonResponse[Nothing]

object MastodonResponses {
  case class Success[+A](elem: A) extends MastodonResponse[A]
}

object MastodonErrors {
  case class JSONParseError(e: Throwable) extends MastodonError
  case class ResponseError(statusCode: StatusCode, e: Throwable) extends MastodonError
}
