package ca.schwitzer.scaladon_soc.http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}

import scala.concurrent.Future

trait HttpsConnection {
  def makeRequest(request: HttpRequest): Future[HttpResponse]
  def makeAuthedRequest(request: HttpRequest, token: AccessToken): Future[HttpResponse]
}

class HttpsConnectionImpl(val baseURI: String)
                         (implicit sys: ActorSystem,
                          mat: ActorMaterializer) extends HttpsConnection {
  private final val flow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] = {
    Http().outgoingConnectionHttps(baseURI)
  }

  override def makeRequest(request: HttpRequest): Future[HttpResponse] = {
    Source.single(request).via(flow).runWith(Sink.head)
  }

  override def makeAuthedRequest(request: HttpRequest, token: AccessToken): Future[HttpResponse] = {
    Source.single(request.addCredentials(token.credentials)).via(flow).runWith(Sink.head)
  }
}
