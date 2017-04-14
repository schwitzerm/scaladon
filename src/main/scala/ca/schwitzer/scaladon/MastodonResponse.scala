package ca.schwitzer.scaladon

import akka.http.scaladsl.model.StatusCode
import play.api.libs.json.{JsPath, JsonValidationError}

sealed trait MastodonResponse[+A]
sealed trait MastodonError extends MastodonResponse[Nothing] {
  def asThrowable: Throwable
}

object MastodonResponses {
  case class Success[+A](elem: A) extends MastodonResponse[A]
}

object MastodonErrors {
  case class JSONParseError(e: Throwable) extends MastodonError {
    def asThrowable: Throwable = new Exception(toString)

    override def toString: String = s"$e"
  }

  case class JSONValidationError(jsErrors: Seq[(JsPath, Seq[JsonValidationError])], e: Throwable) extends MastodonError {
    def asThrowable: Throwable = new Exception(toString)

    override def toString: String = s"$e\n$jsErrors"
  }

  case class ResponseError(statusCode: StatusCode, e: Throwable) extends MastodonError {
    def asThrowable: Throwable = new Exception(toString)

    override def toString: String = s"$statusCode\n$e"
  }
}
