# scaladon
A Mastodon social network API implementation in Scala using Akka HTTP and Akka Streams.

This project is in its infancy, but is ready for more testing.

```scala
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import ca.schwitzer.scaladon.{ResponseSuccess, ResponseFailure}
import ca.schwitzer.scaladon.Mastodon
import ca.schwitzer.scaladon.models._

import scala.concurrent.ExecutionContext.Implicits.global //we need an ExecutionContext

object MyMastodonApp extends App {
  implicit val system = ActorSystem() //an ActorSystem
  implicit val materializer = ActorMaterializer() //and an ActorMaterializer

  val statusFuture: Future[Status] = appFuture.flatMap { app =>
    app.login("my_cool@email.com", "thisshouldreallybesupersecure").flatMap { accessToken =>
      app.toot("I'm tooting from the Scaladon API!", StatusVisibilities.Public)(accessToken).map {
        case ResponseSuccess(status) => status
        case ResponseFailure(statusCode, err) =>
          //ideally, send this to where it needs to go, error handler etc.
          throw new Exception(s"Uh-oh, something went wrong!\nStatus code: $statusCode\nError message: ${err.getMessage}")
      }
    }
  }
  
  --OR--

  val statusFuture: Future[Status] = for {
    app <- Mastodon.createApp("marxism.party", "myapp")
    accessToken <- app.login("my_cool@email.com", "thisshouldreallybesupersecure")
    response <- app.toot("I'm tooting from the Scaladon API!", StatusVisibilities.Public)(accessToken)
  } yield response match {
    case ResponseSuccess(status) => status
    case ResponseFailure(statusCode, err) =>
      //ideally, send this to where it needs to go, error handler etc.
      throw new Exception(s"Uh-oh, something went wrong!\nStatus code: $statusCode\nError message: ${err.getMessage}")
  }
}
```



# What doesn't work
Uploading media, changing user settings (display name/e-mail/avatar/header), and posting toots with attachments.

I seek to resolve this swiftly.



# Etc...
More examples and full code documentation will be coming soon. For now, just take a look at the source code.



# About Project Manager
I'm Mitchell Schwitzer and I enjoy to work on all sorts of things, especially in Scala.

If you have any questions or recommendations, please get to me at mitchell@schwitzer.ca, or at [mellow_@marxism.party](https://marxism.party/@mellow_) on the Fediverse!
