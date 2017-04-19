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

  //TODO: change?? this seems to work fine
  implicit val unitReads: Reads[Unit] = Reads[Unit](js => js.validate[JsObject].map {
    case s: JsObject if s == JsObject.empty => ()
    case _ => throw new Exception("NOT AN EMPTY OBJECT")
  })

  implicit class HttpResponseExtensions(response: HttpResponse) {
    def handleAs[A : Reads](implicit m: Materializer, ec: ExecutionContext): Future[MastodonResponse[A]] = {
      response.status match {
        case s if s.isSuccess() => response.entity.toJsValue.map {
          case MastodonResponses.Success(json) => json.validate[A] match {
            case JsSuccess(elem, _) => MastodonResponses.Success(elem)
            case e: JsError => MastodonErrors.JSONValidationError(e.errors, new Exception("Error validating response JSON."))
          }
          case e: MastodonError => e
        }
        case s if s.isFailure() => response.entity.toJsValue.map {
          case MastodonResponses.Success(json) => json.validate[models.MastodonError] match {
            case JsSuccess(error, _) => MastodonErrors.ResponseError(response.status, new Exception(error.error))
            case e: JsError => MastodonErrors.ResponseError(response.status, new Exception("An unknown error has occurred."))
          }
          case e: MastodonError => e
        }
      }
    }
  }

  implicit class JsValueExtensions(json: JsValue) {
    def toJsonEntity: RequestEntity = {
      HttpEntity(ContentTypes.`application/json`, json.toString())
    }
  }

  implicit class ResponseEntityExtensions(entity: ResponseEntity) {
    def toJsValue(implicit m: Materializer, ec: ExecutionContext): Future[MastodonResponse[JsValue]] = {
      entity.dataBytes.runReduce(_ concat _).map(bs => Try(Json.parse(bs.toArray)) match {
        case Success(json) => MastodonResponses.Success(json)
        case Failure(e) => MastodonErrors.JSONParseError(e)
      })
    }
  }

}
