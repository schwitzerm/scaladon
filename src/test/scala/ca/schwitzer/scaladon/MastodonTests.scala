package ca.schwitzer.scaladon

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._

import scala.concurrent.ExecutionContext

class MastodonTests extends FlatSpec with Matchers with ScalaFutures {
  implicit val defaultPatience = PatienceConfig(timeout = Span(3, Seconds), interval = Span(100, Millis))

  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  whenReady(Mastodon.createApp("marxism.party", "tooter")) { mastodon =>
    whenReady(mastodon.login("schwitzerm@gmail.com", System.getenv("TOOTERPASS"))) { accessToken =>
      "Mastodon" should "fetch all the statuses for account id 1" in {
        whenReady(mastodon.getStatusesOfAccount(1)(accessToken)) {
          case ResponseSuccess(statuses) => println(statuses.length)
          case ResponseFailure(status, e) => throw e
        }
      }
    }
  }
}
