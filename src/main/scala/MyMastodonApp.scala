import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import ca.schwitzer.scaladon.Mastodon
import ca.schwitzer.scaladon.models.AccessToken
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ExecutionContext, Future}

object MyMastodonApp extends LazyLogging {
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val appFuture: Future[Mastodon] = {
    Mastodon.createApp("marxism.party", "tooter")
  }

  val tokenFuture: Future[AccessToken] = {
    appFuture.flatMap(_.login("schwitzerm@gmail.com", System.getenv("TOOTERPASS")))
  }

  def main(args: Array[String]): Unit = {
    for {
      app <- appFuture
      token <- tokenFuture
      acct <- app.Accounts.fetch(1)(token)
    } yield println(acct)
  }
}
