package ca.schwitzer.mastodon_api

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

//  "Mastodon.createApp()" should "return an instantiated Mastodon object" in {
//    whenReady(Mastodon.createApp("marxism.party", "tooter")) { mastodon =>
//      assert(mastodon != null)
//    }
//  }

//  "Mastodon.login()" should "login to a user account and return an access token" in {
//    whenReady(Mastodon.createApp("marxism.party", "tooter")) { mastodon =>
//      whenReady(mastodon.login("schwitzerm@gmail.com", System.getenv("TOOTERPASS"))) { accessToken =>
//        assert(accessToken != null)
//      }
//    }
//  }
//
  "Mastodon.getAccount()" should "fetch a users statuses" in {
    whenReady(Mastodon.createApp("marxism.party", "tooter")) { mastodon =>
      whenReady(mastodon.login("schwitzerm@gmail.com", System.getenv("TOOTERPASS"))) { accessToken =>
        whenReady(mastodon.getStatusesOfAccount(1)(accessToken)) { statuses =>
          assert(statuses != null)
          println(statuses)
        }
      }
    }
  }

//  "Mastodon.toot()" should "make a test toot" in {
//    whenReady(Mastodon.createApp("marxism.party", "tooter")) { mastodon =>
//      whenReady(mastodon.login("schwitzerm@gmail.com", System.getenv("TOOTERPASS"))) { accessToken =>
//        whenReady(mastodon.toot("This is a test toot from a #scala implementation of the #mastodon API!")(accessToken)) { status =>
//          assert(true)
//        }
//      }
//    }
//  }

//  "Mastodon.uploadAttachment()" should "upload a file and return an attachment" in {
//    whenReady(Mastodon.createApp("marxism.party", "tooter")) { mastodon =>
//      whenReady(mastodon.login("schwitzerm@gmail.com", System.getenv("TOOTERPASS"))) { accessToken =>
//        whenReady(mastodon.uploadAttachment(Paths.get("/home/mitchell/downloads/shadow.jpg"))(accessToken)) { attachment =>
//          assert(attachment != null)
//          println(attachment)
//        }
//      }
//    }
//  }
}
