[![Build Status](https://travis-ci.org/schwitzerm/scaladon.svg?branch=master)](https://travis-ci.org/schwitzerm/scaladon)
# scaladon
A Mastodon social network API implementation in Scala using Akka HTTP and Akka Streams.

This project is in its infancy, but is ready for more testing.

Currently supports Scala versions 2.11 and 2.12. 



# What doesn't work
Uploading media, changing user settings (display name/e-mail/avatar/header), and posting toots with attachments.

I seek to resolve this swiftly.



# Including in a project
Simply add the following to your build.sbt file:

```libraryDependencies += "ca.schwitzer" %% "scaladon" % "0.3.1"```



# Examples
Tooting a status (using map/flatMap):
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
  
  val appFuture = Mastodon.createApp("marxism.party", "mycoolapp")
  val tokenFuture = appFuture.flatMap(_.login("my_cool@email.com", "thisshouldreallybsupersecure"))
  
  val statusFuture: Future[Status] = appFuture.flatMap { app =>
    tokenFuture.flatMap { token =>
      app.toot("I'm tooting from the Scaladon API!", StatusVisibilities.Public)(token).map {
        case MastodonResponses.Success(elem) => elem
        case e: MastodonError => throw e.asThrowable
      }
    }
  }
}
```

Fetching an account (using for comprehension):
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
  
  val appFuture = Mastodon.createApp("marxism.party", "mycoolapp")
  val tokenFuture = appFuture.flatMap(_.login("my_cool@email.com", "thisshouldreallybsupersecure"))
  
  val accountFuture: Future[Account] = for {
    app     <- appFuture
    token   <- tokenFuture
    account <- app.Accounts.fetch(1)(token) 
  } yield account match {
    case MastodonResponses.Success(elem) => elem
    case e: MastodonError => throw e.asThrowable  
  }
}
```



# Etc...
More examples and full code documentation will be coming soon. For now, just take a look at the source code.



# About Project Manager
I'm Mitchell Schwitzer and I enjoy to work on all sorts of things, especially in Scala.

If you have any questions or recommendations, please get to me at mitchell@schwitzer.ca, or at [mellow_@marxism.party](https://marxism.party/@mellow_) on the Fediverse!
