package ca.schwitzer.scaladon.models

import akka.http.scaladsl.model.StatusCode
import play.api.libs.json.{JsPath, JsonValidationError}

sealed trait MastodonResponse[+A]
sealed trait MastodonResponseError extends MastodonResponse[Nothing] {
  def asThrowable: Throwable
}

object MastodonResponses {
  case class Success[+A](elem: A) extends MastodonResponse[A]
}

object MastodonErrors {
  case class JSONParseError(e: Throwable) extends MastodonResponseError {
    def asThrowable: Throwable = new Exception(toString)

    override def toString: String = s"$e"
  }

  case class JSONValidationError(jsErrors: Seq[(JsPath, Seq[JsonValidationError])], e: Throwable) extends MastodonResponseError {
    def asThrowable: Throwable = new Exception(toString)

    override def toString: String = s"$e\n$jsErrors"
  }

  case class ResponseError(statusCode: StatusCode, e: Throwable) extends MastodonResponseError {
    def asThrowable: Throwable = new Exception(toString)

    override def toString: String = s"$statusCode\n$e"
  }
}
