package ca.schwitzer.scaladon

import java.io.File
import java.nio.file.{Files, Paths}

import akka.actor.ActorSystem
import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import ca.schwitzer.scaladon.models._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

class Mastodon private(baseURI: String,
               appCredentials: AppCredentials)
              (implicit system: ActorSystem,
               materializer: Materializer,
               ec: ExecutionContext) {
  private val flow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] = Http().outgoingConnectionHttps(baseURI)

  /**
    * Make a non-authorized request to the Mastodon instance.
    * @param request The request to send to the Mastodon instance.
    * @return A future HttpResponse.
    */
  private def makeRequest(request: HttpRequest): Future[HttpResponse] = {
    Source.single(request).via(flow).runWith(Sink.head)
  }

  /**
    * Make an authorized request to the Mastodon instance.
    * @param request The request to send to the Mastodon instance.
    * @param token The AccessToken for the authenticated user.
    * @return A future HttpResponse.
    */
  private def makeAuthorizedRequest(request: HttpRequest, token: AccessToken): Future[HttpResponse] = {
    Source.single(request.addCredentials(token.credentials)).via(flow).runWith(Sink.head)
  }

  /**
    * Log the user into the Mastodon instance and returns a future access token.
    * @param username The username to log in with (for Mastodon, this is the account's e-mail address)
    * @param password The password of the user.
    * @param scopes The scopes to use when logging in.
    * @return A future access token.
    */
  def login(username: String,
            password: String,
            scopes: Seq[String] = Seq("read", "write", "follow")): Future[AccessToken] = {
    val entity = Json.obj(
      "client_id" -> appCredentials.clientId,
      "client_secret" -> appCredentials.clientSecret,
      "grant_type" -> "password",
      "username" -> username,
      "password" -> password,
      "scope" -> scopes.mkString(" ")
    ).toJsonEntity
    val request = HttpRequest(method = HttpMethods.POST, uri = "/oauth/token", entity = entity)

    makeRequest(request).flatMap(xhr => xhr.entity.toJsValue.map {
      case MastodonResponses.Success(json) =>
        val token = (json \ "access_token").as[String]
        AccessToken(HttpCredentials.createOAuth2BearerToken(token))

      case error: MastodonError => throw error.asThrowable
    })
  }

  /**
    * Toot a text status.
    * @param status The status to toot.
    * @param visibility The visibility of the toot.
    * @param inReplyToId An optional id of the status this status should be in reply to.
    * @param spoilerText The spoiler text for a status with a content warning.
    * @param token The AccessToken for the authenticated user.
    * @return A future response that may contain the new status or an error.
    */
  def toot(status: String,
           visibility: StatusVisibility,
           inReplyToId: Option[Int] = None,
           spoilerText: Option[String] = None)
          (token: AccessToken): Future[MastodonResponse[Status]] = {
    Statuses.post(status, Seq.empty, sensitive = false, inReplyToId, spoilerText, visibility)(token)
  }

  /**
    * An object containing the methods described in the "Accounts" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#accounts)
    */
  object Accounts {
    /**
      * Fetch an account.
      * @param id The id of the account to fetch.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the desired account or an error.
      */
    def fetch(id: Int)(token: AccessToken): Future[MastodonResponse[Account]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Account])
    }

    /**
      * Fetch the account of the authenticated user.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the authenticated user's account or an error.
      */
    def fetchCurrent(token: AccessToken): Future[MastodonResponse[Account]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/accounts/verify_credentials")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Account])
    }

    //TODO: def updateInformation()

    /**
      * Fetch the accounts following the given account.
      * @param id The account id to fetch followers accounts for.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the accounts or an error.
      */
    def fetchFollowers(id: Int)(token: AccessToken): Future[MastodonResponse[Seq[Account]]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/followers")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Account]])
    }

    /**
      * Fetch the accounts the given account is following.
      * @param id The account id to fetch following accounts for.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the accounts or an error.
      */
    def fetchFollowing(id: Int)(token: AccessToken): Future[MastodonResponse[Seq[Account]]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/following")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Account]])
    }

    /**
      * Fetch statuses post by the given account.
      * @param id The account id to fetch statuses for.
      * @param onlyMedia Whether or not to fetch only media posts.
      * @param excludeReplies Whether or not to exclude replies.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the statuses or an error.
      */
    def fetchStatuses(id: Int, onlyMedia: Boolean = false, excludeReplies: Boolean = false)
                     (token: AccessToken): Future[MastodonResponse[Seq[Status]]] = {
      val entity = Json.obj(
        "only_media" -> onlyMedia,
        "exclude_replies" -> excludeReplies
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/statuses", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Status]])
    }

    /**
      * Follow the given account.
      * @param id The account id to follow.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the new relationship or an error.
      */
    def follow(id: Int)(token: AccessToken): Future[MastodonResponse[Relationship]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/accounts/$id/follow")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Relationship])
    }

    /**
      * Unfollow the given account.
      * @param id The account id to unfollow.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the new relationship or an error.
      */
    def unfollow(id: Int)(token: AccessToken): Future[MastodonResponse[Relationship]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/accounts/$id/unfollow")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Relationship])
    }

    /**
      * Block the given account.
      * @param id The account id to block.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the new relationship or an error.
      */
    def block(id: Int)(token: AccessToken): Future[MastodonResponse[Relationship]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/accounts/$id/block")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Relationship])
    }

    /**
      * Unblock the given account.
      * @param id The account id to unblock.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the new relationship or an error.
      */
    def unblock(id: Int)(token: AccessToken): Future[MastodonResponse[Relationship]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/accounts/$id/unblock")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Relationship])
    }

    /**
      * Mute the given account.
      * @param id The account id to mute.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the new relationship or an error.
      */
    def mute(id: Int)(token: AccessToken): Future[MastodonResponse[Relationship]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/accounts/$id/mute")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Relationship])
    }

    /**
      * Unmute the given account.
      * @param id The account id to unmute.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the new relationship or an error.
      */
    def unmute(id: Int)(token: AccessToken): Future[MastodonResponse[Relationship]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/accounts/$id/unmute")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Relationship])
    }

    /**
      * Fetch relationships for a list of accounts.
      * @param ids The accounts to fetch relationships for.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the relationships or an error.
      */
    def fetchRelationships(ids: Seq[Int] = Seq.empty)
                          (token: AccessToken): Future[MastodonResponse[Seq[Relationship]]] = {
      val entity = Json.obj(
        "id" -> ids
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/relationships", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Relationship]])
    }

    /**
      * Search accounts.
      * @param query The query to search for.
      * @param limit The limit of accounts to return.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the resulting accounts or an error.
      */
    def search(query: String, limit: Int = 40)
              (token: AccessToken): Future[MastodonResponse[Seq[Account]]] = {
      val entity = Json.obj(
        "q" -> query,
        "limit" -> limit
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/accounts/search", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Account]])
    }
  }

  /**
    * An object containing the methods described in the "Blocks" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#blocks)
    */
  object Blocks {
    /**
      * Fetch accounts the authenticated user is blocking.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the blocked accounts or an error.
      */
    def fetch(token: AccessToken): Future[MastodonResponse[Seq[Account]]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/blocks")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Account]])
    }
  }

  /**
    * An object containing the methods described in the "Favourites" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#favourites)
    */
  object Favourites {
    /**
      * Fetch statuses the authenticated user has favourited.
      * @param token The AccessToken for the authenticated user.
      * @return A future response that may contain the favourited statuses or an error.
      */
    def fetch(token: AccessToken): Future[MastodonResponse[Seq[Status]]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/favourites")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Status]])
    }
  }

  /**
    * An object containing the methods described in the "Follows" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#follows)
    */
  object Follows {
    /**
      * Follow a user by their user URI.
      * @param userUri The user URI of the account to follow.
      * @param token The AccessToken for the authenticated user.
      * @return A future response containing the followed account or an error.
      */
    def follow(userUri: String)(token: AccessToken): Future[MastodonResponse[Account]] = {
      val entity = Json.obj(
        "uri" -> userUri
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.POST, uri = "/api/v1/follows", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Account])
    }
  }

  /**
    * An object containing the methods described in the "Instances" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#instances)
    */
  object Instances {
    /**
      * Fetch information about the connected instance.
      * @return A future response containing the instance information or an error.
      */
    def fetchInformation: Future[MastodonResponse[Instance]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/instance")

      makeRequest(request).flatMap(_.handleAs[Instance])
    }
  }

  /**
    * An object containing the methods described in the "Mutes" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#mutes)
    */
  object Mutes {
    /**
      * Fetch accounts the authenticated user is muting.
      * @param token The AccessToken for the authenticated user.
      * @return A future response containing the muted accounts or an error.
      */
    def fetch(token: AccessToken): Future[MastodonResponse[Seq[Account]]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/mutes")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Account]])
    }
  }

  /**
    * An object containing the methods described in the "Notifications" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#notifications)
    */
  object Notifications {
    /**
      * Clears notifications.
      * @param token The AccessToken for the authenticated user.
      * @return A future response containing a Unit or an error.
      */
    def clear(token: AccessToken): Future[MastodonResponse[Unit]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/notifications/clear")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Unit])
    }

    /**
      * Fetches notifications.
      * @param token The AccessToken for the authenticated user.
      * @return A future response containing the notifications or an error.
      */
    def fetch(token: AccessToken): Future[MastodonResponse[Seq[Notification]]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/notifications")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Notification]])
    }

    /**
      * Fetches a notification.
      * @param id The id of the notification to fetch.
      * @param token The AccessToken for the authenticated user.
      * @return A future response containing the notification or an error.
      */
    def fetch(id: Int)(token: AccessToken): Future[MastodonResponse[Notification]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/notifications/$id")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Notification])
    }
  }

  /**
    * An object containing the methods described in the "Reports" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#reports)
    */
  object Reports {
    /**
      * Fetches reports made by the authenticated user.
      * @param token The AccessToken for the authenticated user.
      * @return A future response containing the reports or an error.
      */
    def fetch(token: AccessToken): Future[MastodonResponse[Seq[Report]]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/reports")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Report]])
    }

    /**
      * Report the status of a user.
      * @param accountId The account id of the user to report.
      * @param statusIds The statuses to report.
      * @param comment A comment about the report
      * @param token The AccessToken for the authenticated user.
      * @return A future response containing the report or an error.
      */
    def report(accountId: Int, statusIds: Seq[Int], comment: String)
              (token: AccessToken): Future[MastodonResponse[Report]] = {
      val entity = Json.obj(
        "account_id" -> accountId,
        "status_ids" -> statusIds,
        "comment" -> comment
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.POST, uri = "/api/v1/reports", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Report])
    }
  }

  /**
    * An object containing the methods described in the "Requests" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#requests)
    */
  object Requests {
    /**
      * Fetch follow requests
      * @param token The AccessToken for the authenticated user.
      * @return A future response containing a the accounts requesting to follow the user or an error.
      */
    def fetchFollows(token: AccessToken): Future[MastodonResponse[Seq[Account]]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/follow_requests")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Account]])
    }
    
    def authorizeFollow(id: Int)(token: AccessToken): Future[MastodonResponse[Unit]] = {
      val entity = Json.obj(
        "id" -> id
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.POST, uri = "/api/v1/follow_requests/authorize", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Unit])
    }

    def rejectFollow(id: Int)(token: AccessToken): Future[MastodonResponse[Unit]] = {
      val entity = Json.obj(
        "id" -> id
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.POST, uri = "/api/v1/follow_requests/reject", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Unit])
    }
  }

  /**
    * An object containing the methods described in the "Search" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#search)
    */
  object Search {
    def content(query: String, resolveNonLocal: Boolean = false)
               (token: AccessToken): Future[MastodonResponse[Results]] = {
      val entity = Json.obj(
        "q" -> query,
        "resolve" -> resolveNonLocal
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/search", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Results])
    }
  }

  /**
    * An object containing the methods described in the "Statuses" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#statuses)
    */
  object Statuses {
    def fetch(id: Int)(token: AccessToken): Future[MastodonResponse[Status]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/statuses/$id")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Status])
    }

    def fetchContext(id: Int)(token: AccessToken): Future[MastodonResponse[Context]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/statuses/$id/context")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Context])
    }

    def fetchCard(id: Int)(token: AccessToken): Future[MastodonResponse[Card]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/statuses/$id/card")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Card])
    }

    def favouritedBy(id: Int)(token: AccessToken): Future[MastodonResponse[Seq[Account]]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/statuses/$id/favourited_by")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Account]])
    }

    def rebloggedBy(id: Int)(token: AccessToken): Future[MastodonResponse[Seq[Account]]] = {
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/statuses/$id/reblogged_by")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Account]])
    }

    //TODO: make public once media upload is fixed
    def post(status: String,
             mediaIds: Seq[Int],
             sensitive: Boolean,
             inReplyToId: Option[Int] = None,
             spoilerText: Option[String] = None,
             visibility: StatusVisibility = StatusVisibilities.Public)
            (token: AccessToken): Future[MastodonResponse[Status]] = {
      val entity = Json.obj(
        "status" -> status,
        "media_ids" -> mediaIds,
        "sensitive" -> sensitive,
        "in_reply_to_id" -> inReplyToId,
        "spoiler_text" -> spoilerText.asInstanceOf[Option[String]],
        "visibility" -> visibility.toString
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/statuses", entity = entity)
      println(entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Status])
    }

    def delete(id: Int)(token: AccessToken): Future[MastodonResponse[Unit]] = {
      val request = HttpRequest(method = HttpMethods.DELETE, uri = s"/api/v1/statuses/$id")

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Unit])
    }

    def reblog(id: Int)(accessToken: AccessToken): Future[MastodonResponse[Status]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/statuses/$id/reblog")

      makeAuthorizedRequest(request, accessToken).flatMap(_.handleAs[Status])
    }

    def unreblog(id: Int)(accessToken: AccessToken): Future[MastodonResponse[Status]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/statuses/$id/unreblog")

      makeAuthorizedRequest(request, accessToken).flatMap(_.handleAs[Status])
    }

    def favourite(id: Int)(accessToken: AccessToken): Future[MastodonResponse[Status]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/status/$id/favourite")

      makeAuthorizedRequest(request, accessToken).flatMap(_.handleAs[Status])
    }

    def unfavourite(id: Int)(accessToken: AccessToken): Future[MastodonResponse[Status]] = {
      val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/status/$id/unfavourite")

      makeAuthorizedRequest(request, accessToken).flatMap(_.handleAs[Status])
    }
  }

  /**
    * An object containing the methods described in the "Timelines" section of the Mastodon API documentation.
    * (https://github.com/tootsuite/documentation/blob/master/Using-the-API/API.md#timelines)
    */
  object Timelines {
    def fetchHome(localOnly: Boolean = false)(token: AccessToken): Future[MastodonResponse[Seq[Status]]] = {
      val entity = Json.obj(
        "local" -> localOnly
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/timelines/home", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Status]])
    }

    def fetchPublic(localOnly: Boolean = false)(token: AccessToken): Future[MastodonResponse[Seq[Status]]] = {
      val entity = Json.obj(
        "local" -> localOnly
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/timelines/public", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Status]])
    }

    def fetchForHashtag(hashtag: String, localOnly: Boolean = false)
                       (token: AccessToken): Future[MastodonResponse[Seq[Status]]] = {
      val entity = Json.obj(
        "local" -> localOnly
      ).toJsonEntity
      val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/timelines/tag/$hashtag", entity = entity)

      makeAuthorizedRequest(request, token).flatMap(_.handleAs[Seq[Status]])
    }
  }
}

object Mastodon {
  final val DEFAULT_STORAGE_LOC: String = System.getProperty("user.home") + "/.scaladon"
  final val DEFAULT_REDIRECT_URI: String = "urn:ietf:wg:oauth:2.0:oob"

  private def loadAppData(baseURI: String, clientName: String): Option[AppCredentials] = {
    val file = s"$DEFAULT_STORAGE_LOC/$baseURI/apps/$clientName.mdon"

    if (Files.exists(Paths.get(file))) { Some(Json.parse(io.Source.fromFile(file).getLines().mkString).as[AppCredentials]) }
    else { None }
  }

  private def saveAppData(baseURI: String, clientName: String, credentials: AppCredentials): Unit = {
    import java.io._

    val file = s"$DEFAULT_STORAGE_LOC/$baseURI/apps/$clientName.mdon"
    val data = Json.toJson(credentials).toString
    val writer = new PrintWriter(new File(file))
    writer.write(data)
    writer.close()
  }

  def createApp(baseURI: String,
                clientName: String,
                scopes: Seq[String] = Seq("read", "write", "follow"),
                redirectURIs: Seq[String] = Seq(DEFAULT_REDIRECT_URI))
               (implicit system: ActorSystem,
                materializer: Materializer,
                ec: ExecutionContext): Future[Mastodon] = {
    val dir = s"$DEFAULT_STORAGE_LOC/$baseURI/apps"
    //check directory here, everything else that relies on it occurs beyond.
    if(Files.notExists(Paths.get(dir))) {
      if(!new File(dir).mkdirs()) {
        Future.failed(new Exception(s"Could not create or find API storage directory for this network: $dir"))
      }
    }

    loadAppData(baseURI, clientName) match {
      case Some(credentials) => Future.successful(new Mastodon(baseURI, credentials))
      case None =>
        val entity = Json.obj(
          "client_name" -> clientName,
          "scopes" -> scopes.mkString(" "),
          "redirect_uris" -> redirectURIs.mkString(" ")
        ).toJsonEntity
        val request = HttpRequest(method = HttpMethods.POST, uri = s"https://$baseURI/api/v1/apps", entity = entity)

        Http().singleRequest(request).flatMap(xhr =>
          xhr.handleAs[AppCredentials].map {
            case MastodonResponses.Success(creds) =>
              saveAppData(baseURI, clientName, creds)
              new Mastodon(baseURI, creds)
            case error: MastodonError => throw error.asThrowable
          }
        )
    }
  }
}
