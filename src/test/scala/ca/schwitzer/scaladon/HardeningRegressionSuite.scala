package ca.schwitzer.scaladon

import cats.effect.{IO, Ref}
import cats.syntax.applicative.*
import java.nio.charset.StandardCharsets
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Response as Http4sResponse, Status as Http4sStatus}
import org.http4s.client.Client

import ca.schwitzer.scaladon.domain.{
  ApiError,
  ApiException,
  HttpStatus,
  Id,
  MediaType,
  OAuthGrantType
}
import ca.schwitzer.scaladon.http.{
  BearerToken,
  Host,
  HttpBackend,
  HttpMethod,
  MultipartPart,
  Request,
  Response
}
import ca.schwitzer.scaladon.http4s.Http4sBackend

/** The join for the hardening plan: each case exercises one fix through the public client/backend
  * seams and must fail when that fix is reverted.
  *
  * - H-01 token redaction: [[toString]] of a captured request never renders the bearer token or a
  *   credential-bearing OAuth form value.
  * - H-02 status media: `Statuses.delete` decodes `media_attachments` from the redraft response.
  * - H-03 multipart: every upload gets a fresh boundary with matching framing, and a hostile part
  *   content type fails before the transport reaches the client.
  */
class HardeningRegressionSuite extends CatsEffectSuite:

  private val host  = Host("code.party")
  private val token = BearerToken("hardening-secret-token")

  private val statusJson: String =
    """{
      |  "id": "42",
      |  "uri": "tag:code.party,2017-04-05:objectId=1",
      |  "url": "https://code.party/@alice/42",
      |  "account": {
      |    "id": "7",
      |    "username": "alice",
      |    "acct": "alice",
      |    "display_name": "Alice",
      |    "note": "",
      |    "url": "https://code.party/@alice",
      |    "avatar": "https://code.party/a.png",
      |    "header": "https://code.party/h.png",
      |    "locked": false,
      |    "created_at": "2017-04-05T12:00:00.000Z"
      |  },
      |  "content": "<p>Hello</p>",
      |  "created_at": "2017-04-05T12:00:00.000Z",
      |  "visibility": "public",
      |  "spoiler_text": "",
      |  "reblog": null,
      |  "reblogs_count": 0,
      |  "favourites_count": 0
      |}""".stripMargin

  /** The `DELETE /api/v1/statuses/:id` answer for delete-and-redraft, including its media. */
  private val redraftJson: String =
    """{
      |  "id": "42",
      |  "uri": "tag:code.party,2017-04-05:objectId=1",
      |  "url": "https://code.party/@alice/42",
      |  "account": {
      |    "id": "7",
      |    "username": "alice",
      |    "acct": "alice",
      |    "display_name": "Alice",
      |    "note": "",
      |    "url": "https://code.party/@alice",
      |    "avatar": "https://code.party/a.png",
      |    "header": "https://code.party/h.png",
      |    "locked": false,
      |    "created_at": "2017-04-05T12:00:00.000Z"
      |  },
      |  "content": "<p>Hello</p>",
      |  "created_at": "2017-04-05T12:00:00.000Z",
      |  "visibility": "public",
      |  "spoiler_text": "",
      |  "reblog": null,
      |  "reblogs_count": 0,
      |  "favourites_count": 0,
      |  "media_attachments": [
      |    {
      |      "id": "223457",
      |      "type": "image",
      |      "url": "https://code.party/media/a.png",
      |      "preview_url": "https://code.party/media/a_small.png",
      |      "remote_url": null,
      |      "text_url": null,
      |      "description": "a diagram",
      |      "blurhash": "UBL_:rOpGG-oBUNG"
      |    }
      |  ],
      |  "text": "hello world"
      |}""".stripMargin

  private val oauthTokenJson: String =
    """{
      |  "access_token": "oauth-access-token",
      |  "token_type": "Bearer",
      |  "scope": "read write",
      |  "created_at": 1720000000
      |}""".stripMargin

  private val pushSubscriptionJson: String =
    """{
      |  "id": 328183,
      |  "endpoint": "https://push.example/xyz",
      |  "standard": true,
      |  "server_key": "server-key",
      |  "alerts": {},
      |  "policy": "all"
      |}""".stripMargin

  private val mediaJson: String =
    """{
      |  "id": "1",
      |  "type": "image",
      |  "url": "https://code.party/media/a.png",
      |  "preview_url": "https://code.party/media/a_small.png",
      |  "remote_url": null,
      |  "text_url": null,
      |  "description": null,
      |  "blurhash": null
      |}""".stripMargin

  private def recording(response: Response): IO[(Ref[IO, List[Request]], Mastodon[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Mastodon.live(backend, host, token)
    }

  test("H-01: an authenticated request renders without its bearer token") {
    for
      pair      <- recording(Response(HttpStatus.Ok, statusJson))
      (ref, mastodon) = pair
      _         <- mastodon.statuses.post("hello")
      requests  <- ref.get
    yield
      val request = requests.head
      assertEquals(request.headers.get("Authorization"), Some(s"Bearer ${token.value}"))
      val rendered = request.toString
      assert(rendered.contains("**redacted**"), rendered)
      assert(!rendered.contains(token.value), rendered)
  }

  test("H-01: an OAuth form renders client_secret and code redacted") {
    for
      pair      <- recording(Response(HttpStatus.Ok, oauthTokenJson))
      (ref, mastodon) = pair
      _ <- mastodon.oauth.token(
             OAuthGrantType.AuthorizationCode,
             "client-id",
             "client-secret-value",
             code = Some("authorization-code-value"),
             redirectUri = Some("https://client.example/callback")
           )
      requests <- ref.get
    yield
      val rendered = requests.head.toString
      assert(rendered.contains("client_secret=**redacted**"), rendered)
      assert(rendered.contains("code=**redacted**"), rendered)
      assert(!rendered.contains("client-secret-value"), rendered)
      assert(!rendered.contains("authorization-code-value"), rendered)
      assert(rendered.contains("grant_type=authorization_code"), rendered)
  }

  test("H-01: revoke, PKCE and push forms redact every credential") {
    for
      revokePair <- recording(Response(HttpStatus.Ok, "{}"))
      (revokeRef, revokeClient) = revokePair
      _ <- revokeClient.oauth.revoke("ACCESS-TOKEN-XYZ", "client-id", "client-secret-value")
      revokeRequests <- revokeRef.get
      tokenPair <- recording(Response(HttpStatus.Ok, oauthTokenJson))
      (tokenRef, tokenClient) = tokenPair
      _ <- tokenClient.oauth.token(
             OAuthGrantType.AuthorizationCode,
             "client-id",
             "client-secret-value",
             code = Some("authorization-code-value"),
             redirectUri = Some("https://client.example/callback"),
             codeVerifier = Some("PKCE-VERIFIER-XYZ")
           )
      tokenRequests <- tokenRef.get
      pushPair <- recording(Response(HttpStatus.Ok, pushSubscriptionJson))
      (pushRef, pushClient) = pushPair
      _ <- pushClient.push.create("https://push.example/xyz", "p256dh-key", "push-auth-secret")
      pushRequests <- pushRef.get
    yield
      val revoke = revokeRequests.head.toString
      assert(revoke.contains("token=**redacted**"), revoke)
      assert(!revoke.contains("ACCESS-TOKEN-XYZ"), revoke)
      val exchange = tokenRequests.head.toString
      assert(exchange.contains("code_verifier=**redacted**"), exchange)
      assert(!exchange.contains("PKCE-VERIFIER-XYZ"), exchange)
      val push = pushRequests.head.toString
      assert(push.contains("subscription[keys][auth]=**redacted**"), push)
      assert(!push.contains("push-auth-secret"), push)
      assert(push.contains("subscription[keys][p256dh]=p256dh-key"), push)
  }

  test("H-02: delete-and-redraft keeps the media attachments") {
    for
      pair      <- recording(Response(HttpStatus.Ok, redraftJson))
      (ref, mastodon) = pair
      deleted   <- mastodon.statuses.delete(Id("42"))
      requests  <- ref.get
    yield
      assertEquals(deleted.text, Some("hello world"))
      assertEquals(deleted.mediaAttachments.map(_.id.value), List("223457"))
      assertEquals(deleted.mediaAttachments.map(_.kind), List(MediaType.Image))
      assertEquals(
        deleted.mediaAttachments.flatMap(_.url),
        List("https://code.party/media/a.png")
      )
      assertEquals(requests.head.method, HttpMethod.DELETE)
  }

  private final case class Seen(request: org.http4s.Request[IO], body: String)

  private def http4sBackend(
      status: Http4sStatus,
      responseBody: String
  ): IO[(Ref[IO, List[Seen]], HttpBackend[IO])] =
    Ref.of[IO, List[Seen]](Nil).map { ref =>
      val app = HttpApp[IO] { request =>
        request.as[String].flatMap { text =>
          val response = Http4sResponse[IO](status).withEntity(responseBody)
          ref.update(_ :+ Seen(request, text)) *> response.pure[IO]
        }
      }
      ref -> Http4sBackend.fromClient(Client.fromHttpApp(app))
    }

  test("H-03: uploads use distinct boundaries and reject header injection") {
    val file = MultipartPart.file(
      "file",
      "cat.png",
      "image/png",
      "PNGDATA".getBytes(StandardCharsets.UTF_8)
    )
    val hostile = MultipartPart.file(
      "file",
      "evil.txt",
      "image/png\r\nX-Injected: 1",
      "DATA".getBytes(StandardCharsets.UTF_8)
    )
    for
      pair      <- http4sBackend(Http4sStatus.Ok, mediaJson)
      (ref, http) = pair
      client    = Mastodon.live(http, host, token)
      _         <- client.media.upload(file)
      _         <- client.media.upload(file)
      seen      <- ref.get
      rejected  <- client.media.upload(hostile).attempt
      after     <- ref.get
    yield
      assertEquals(seen.size, 2)
      val boundaries = seen.map(
        _.request.contentType.flatMap(_.mediaType.extensions.get("boundary"))
      )
      assert(boundaries.forall(_.isDefined), s"missing boundary: $boundaries")
      val values = boundaries.flatten
      assertEquals(values.size, 2)
      assertNotEquals(values(0), values(1))
      seen.zip(values).foreach { case (entry, boundary) =>
        assert(entry.body.startsWith(s"--$boundary\r\n"), entry.body)
        assert(entry.body.endsWith(s"--$boundary--\r\n"), entry.body)
      }
      rejected match
        case Left(ApiException(ApiError.Transport(cause))) =>
          assert(cause.getMessage.contains("content type"), cause.getMessage)
        case other => fail(s"expected ApiException(Transport), got $other")
      assertEquals(after.size, 2, "the hostile upload must not reach the client")
  }
