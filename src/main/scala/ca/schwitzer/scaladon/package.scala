package ca.schwitzer

import akka.http.scaladsl.model._
import akka.stream.Materializer
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

package object scaladon {

  final val dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"

  implicit val dateReads: Reads[DateTime] = Reads[DateTime](js =>
    js.validate[String].map(str =>
      DateTime.parse(str, DateTimeFormat.forPattern(dateFormat))
    )
  )

  //region Wrappers

  sealed abstract class Response[+A] extends Product with Serializable {
    def get: A
  }

  case class ResponseSuccess[A](value: A) extends Response[A] {
    override def get: A = value
  }

  case class ResponseFailure(statusCode: StatusCode, throwable: Throwable) extends Response[Nothing] {
    override def get = throw new NoSuchElementException("ResponseFailure.get")
  }

  implicit class JsValueExtensions(json: JsValue) {
    def toJsonEntity: RequestEntity = {
      HttpEntity(ContentTypes.`application/json`, json.toString())
    }
  }

  private sealed abstract class ResponseEntityWrapper
  private case class ResponseEntitySuccess(json: JsValue) extends ResponseEntityWrapper
  private case class ResponseEntityFailure(e: Throwable) extends ResponseEntityWrapper

  //endregion Wrappers

  implicit class HttpResponseExtensions(response: HttpResponse) {
    def handleAsResponse[A : Reads](implicit m: Materializer, ec: ExecutionContext): Future[Response[A]] = {
      response.status match {
        case s if s.isSuccess() => response.entity.toFutureJsValue.map {
          case ResponseEntitySuccess(json) => json.validate[A] match {
            case s: JsSuccess[A] => ResponseSuccess(s.get)
            case e: JsError => ResponseFailure(response.status, new Exception(JsError.toJson(e).toString))
          }
          case ResponseEntityFailure(e) => ResponseFailure(response.status, e)
        }
        case _ => response.entity.toFutureJsValue.map {
          case ResponseEntitySuccess(json) => json.validate[models.Error] match {
            case s: JsSuccess[A] => ResponseSuccess(s.get)
            case e: JsError => ResponseFailure(response.status, new Exception(JsError.toJson(e).toString))
          }
          case ResponseEntityFailure(e) => ResponseFailure(response.status, e)
        }
      }
    }
  }

  implicit class ResponseEntityExtensions(entity: ResponseEntity) {
    def toFutureJsValue(implicit m: Materializer, ec: ExecutionContext): Future[ResponseEntityWrapper] = {
      entity.dataBytes.runReduce(_ concat _).map{bs => Try(Json.parse(bs.toArray)) match {
        case Success(json) => ResponseEntitySuccess(json)
        case Failure(e) => ResponseEntityFailure(e)
      }}
    }
  }

}
