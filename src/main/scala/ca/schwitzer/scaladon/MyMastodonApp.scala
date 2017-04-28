package ca.schwitzer.scaladon

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext

object MyMastodonApp extends App {
  implicit val sys: ActorSystem = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  val appFuture = Mastodon.createApp("marxism.party", "tooter")
  val tokenFuture = appFuture.flatMap(_.login("schwitzerm@gmail.com", System.getenv("TOOTERPASS")))

  val stream = for {
    app <- appFuture
    token <- tokenFuture
    stream <- app.Streaming.user(token)
  } yield stream

  stream.map(_.runForeach(println))
}
