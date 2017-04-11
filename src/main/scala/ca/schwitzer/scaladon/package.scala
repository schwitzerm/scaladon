package ca.schwitzer

import akka.http.scaladsl.model._
import akka.stream.Materializer
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

package object scaladon {

  final val dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"

  implicit val dateReads: Reads[DateTime] = Reads[DateTime](js =>
    js.validate[String].map(str =>
      DateTime.parse(str, DateTimeFormat.forPattern(dateFormat))
    )
  )

  implicit class JsValueExtensions(json: JsValue) {
    def toJsonEntity: RequestEntity = {
      HttpEntity(ContentTypes.`application/json`, json.toString())
    }
  }

  implicit class HttpResponseExtensions(response: HttpResponse) {
    def mapSuccess[A](f: (HttpResponse) => A): A = {
      if(response.status.isFailure()) { throw new Exception(s"HttpRequest failed with error: ${response.status}") }
      else { f(response) }
    }

    def transformSuccessEntityTo[A: Reads](implicit m: Materializer, ec: ExecutionContext): Future[A] = {
      response.mapSuccess(_.entity.toFutureJsValue.map(_.as[A]))
    }
  }

  implicit class ResponseEntityExtensions(entity: ResponseEntity) {
    def toFutureJsValue(implicit materializer: Materializer,
                        ec: ExecutionContext): Future[JsValue] = {
      entity.dataBytes.runReduce(_ concat _).map(bs => Json.parse(bs.toArray))
    }
  }

}
