# scaladon
A Mastodon social network API implementation in Scala using Akka HTTP and Akka Streams.

This project is in its infancy, but is ready for more testing.

```scala
package ca.schwitzer.scaladon

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import ca.schwitzer.scaladon.Mastodon
import ca.schwitzer.scaladon.models._

import scala.concurrent.ExecutionContext.Implicits.global //we need an ExecutionContext
import scala.concurrent.Future

object MyMastodonApp extends App {
  implicit val system = ActorSystem() //an ActorSystem
  implicit val materializer = ActorMaterializer() //and an ActorMaterializer

  val appFuture = Mastodon.createApp("marxism.party", "myapp")
  val tokenFuture = appFuture.flatMap(_.login("my_cool@email.com", "thisshouldreallybesupersecure"))
  
  val statusFuture: Future[Status] = for {
    app <- appFuture,
    token <- tokenFuture,
  } yield app.toot("I'm tooting from the Scaladon API!", StatusVisibilities.Public)(token).map {
    case MastodonResponses.Success(status) => status
    case error: MastodonError => throw error.asThrowable //or send to error handler, or log, or print status etcetc  
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
