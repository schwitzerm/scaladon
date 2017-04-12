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

  private def makeRequest(request: HttpRequest): Future[HttpResponse] = {
    Source.single(request).via(flow).runWith(Sink.head)
  }

  private def makeAuthorizedRequest(request: HttpRequest, accessToken: AccessToken): Future[HttpResponse] = {
    Source.single(request.addCredentials(accessToken.credentials)).via(flow).runWith(Sink.head)
  }

  /**
    * Logs the user into the Mastodon instance and returns a future access token.
    *
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
      "scopes" -> scopes.mkString(" ")
    ).toJsonEntity
    val request = HttpRequest(method = HttpMethods.POST, uri = "/oauth/token", entity = entity)

    makeRequest(request).flatMap(xhr => xhr.entity.toJsValue.map { json =>
        val token = (json \ "access_token").as[String]
        AccessToken(HttpCredentials.createOAuth2BearerToken(token))
    })
  }

  //region Accounts

  def getAccount(id: Int)(accessToken: AccessToken): Future[Response[Account]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Account])
  }

  def getCurrentUserAccount(accessToken: AccessToken): Future[Response[Account]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/accounts/verify_credentials")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Account])
  }

  //TODO: updateCurrentUserAccount()

  def getFollowersOfAccount(id: Int)(accessToken: AccessToken): Future[Response[Seq[Account]]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/followers")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Seq[Account]])
  }

  def getFollowingOfAccount(id: Int)(accessToken: AccessToken): Future[Response[Seq[Account]]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/following")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Seq[Account]])
  }

  def getStatusesOfAccount(id: Int, onlyMedia: Boolean = false, excludeReplies: Boolean = false)
                          (accessToken: AccessToken): Future[Response[Seq[Status]]] = {
    val entity = Json.obj(
      "only_media" -> onlyMedia,
      "exclude_replies" -> excludeReplies
    ).toJsonEntity
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/statuses", entity = entity)

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Seq[Status]])
  }

  def followAccount(id: Int)(accessToken: AccessToken): Future[Response[Account]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/follow")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Account])
  }

  def unfollowAccount(id: Int)(accessToken: AccessToken): Future[Response[Account]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/unfollow")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Account])
  }

  def blockAccount(id: Int)(accessToken: AccessToken): Future[Response[Account]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/block")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Account])
  }

  def unblockAccount(id: Int)(accessToken: AccessToken): Future[Response[Account]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/unblock")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Account])
  }

  def muteAccount(id: Int)(accessToken: AccessToken): Future[Response[Account]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/mute")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Account])
  }

  def unmuteAccount(id: Int)(accessToken: AccessToken): Future[Response[Account]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$id/unmute")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Account])
  }

  def getAccountRelationships(ids: Seq[Int] = Seq.empty)
                             (accessToken: AccessToken): Future[Response[Seq[Relationship]]] = {
    val entity = Json.obj(
      "id" -> ids
    ).toJsonEntity
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/relationships", entity = entity)

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Seq[Relationship]])
  }

  def searchAccounts(query: String, limit: Int = 40)(accessToken: AccessToken): Future[Response[Seq[Account]]] = {
    val entity = Json.obj(
      "q" -> query,
      "limit" -> limit
    ).toJsonEntity
    val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/accounts/search", entity = entity)

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Seq[Account]])
  }


  //endregion Accounts

  //region Blocks

  def getBlocks(accessToken: AccessToken): Future[Response[Seq[Account]]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/blocks")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Seq[Account]])
  }

  //endregion Blocks

  //region Favourites

  def getFavourites(accessToken: AccessToken): Future[Response[Seq[Status]]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/favourites")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Seq[Status]])
  }

  //endregion Favourites

  //region Follows

  def followUser(uri: String)(accessToken: AccessToken): Future[Response[Account]] = {
    val entity = Json.obj(
      "uri" -> uri
    ).toJsonEntity
    val request = HttpRequest(method = HttpMethods.POST, uri = "/api/v1/follows", entity = entity)

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Account])
  }

  //endregion Follows

  //region Instances

  def getInstanceInformation: Future[Response[Instance]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/instance")

    makeRequest(request).flatMap(_.handleAsResponse[Instance])
  }

  //endregion Instances

  //region Media

  //endregion Media


  //region Mutes

  def getMutes(accessToken: AccessToken): Future[Response[Seq[Account]]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/mutes")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Seq[Account]])
  }

  //endregion Mutes

  //region Notifications

  def getNotification(id: Int)(accessToken: AccessToken): Future[Response[Notification]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/notifications/$id")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Notification])
  }

  def getNotifications(accessToken: AccessToken): Future[Response[Seq[Notification]]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/notifications")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Seq[Notification]])
  }

  //endregion Notifications

  //region Requests

  def getFollowRequests(accessToken: AccessToken): Future[Response[Seq[Account]]] = {
    val request = HttpRequest(method = HttpMethods.GET, uri = "/api/v1/follow_requests")

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Seq[Account]])
  }

  def authorizeFollowRequest(id: Int)(accessToken: AccessToken): Future[Unit] = {
    val entity = Json.obj(
      "id" -> id
    ).toJsonEntity
    val request = HttpRequest(method = HttpMethods.POST, uri = "/api/v1/follow_requests/authorize", entity = entity)

    makeAuthorizedRequest(request, accessToken).map {
      case xhr if xhr.status.isSuccess() => ()
      case xhr => throw new Exception(s"HttpRequest failed with error: ${xhr.status}")
    }
  }

  def rejectFollowRequest(id: Int)(accessToken: AccessToken): Future[Unit] = {
    val entity = Json.obj(
      "id" -> id
    ).toJsonEntity
    val request = HttpRequest(method = HttpMethods.POST, uri = "/api/v1/follow_requests/reject", entity = entity)

    makeAuthorizedRequest(request, accessToken).map {
      case xhr if xhr.status.isSuccess() => ()
      case xhr => throw new Exception(s"HttpRequest failed with error: ${xhr.status}")
    }
  }

  //endregion Requests

  //region Statuses

  def postStatus(status: String,
                 mediaIds: Seq[Int],
                 sensitive: Boolean,
                 inReplyToId: Option[Int] = None,
                 spoilerText: Option[String] = None,
                 visibility: StatusVisibility = StatusVisibilities.Public)
                (accessToken: AccessToken): Future[Response[Status]] = {
    val st = spoilerText.getOrElse("")
    val entity = Json.obj(
      "status" -> status,
      "media_ids" -> mediaIds,
      "sensitive" -> sensitive,
      "in_reply_to_id" -> inReplyToId,
      "spoiler_text" -> st,
      "visibility" -> visibility.toString
    ).toJsonEntity
    val request = HttpRequest(method = HttpMethods.POST, uri = s"/api/v1/statuses", entity = entity)
    println(entity)

    makeAuthorizedRequest(request, accessToken).flatMap(_.handleAsResponse[Status])
  }

  def toot(status: String)(accessToken: AccessToken): Future[Response[Status]] = {
    postStatus(status, Seq.empty, sensitive = false, None, None, StatusVisibilities.Public)(accessToken)
  }

  //endregion Statuses
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
          xhr.handleAsResponse[AppCredentials].map {
            case ResponseSuccess(creds) =>
              saveAppData(baseURI, clientName, creds)
              new Mastodon(baseURI, creds)
            case ResponseFailure(status, e) => throw new Exception(s"Status: $status\nException: $e")
          }
        )
    }
  }
}
