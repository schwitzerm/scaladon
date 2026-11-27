package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class AnnouncementsSuite extends CatsEffectSuite:

  private val host  = Host("code.party")
  private val token = BearerToken("secret")

  private val announcementJson: String =
    """{
      |  "id": "8",
      |  "content": "<p>An important server update</p>",
      |  "starts_at": null,
      |  "ends_at": null,
      |  "all_day": false,
      |  "published_at": "2026-07-03T01:27:38.726Z",
      |  "updated_at": "2026-07-03T01:27:38.752Z",
      |  "read": true,
      |  "mentions": [
      |    {
      |      "id": "1",
      |      "username": "alice",
      |      "url": "https://code.party/@alice",
      |      "acct": "alice"
      |    }
      |  ],
      |  "statuses": [
      |    { "id": "42", "url": "https://code.party/@alice/42" }
      |  ],
      |  "tags": [
      |    { "name": "server", "url": "https://code.party/tags/server" }
      |  ],
      |  "emojis": [
      |    {
      |      "shortcode": "blobaww",
      |      "url": "https://files.code.party/blobaww.png",
      |      "static_url": "https://files.code.party/blobaww_static.png",
      |      "visible_in_picker": true,
      |      "category": "Blobs"
      |    }
      |  ],
      |  "reactions": [
      |    {
      |      "name": "bongoCat",
      |      "count": 9,
      |      "me": false,
      |      "url": "https://files.code.party/bongo.png",
      |      "static_url": "https://files.code.party/bongo_static.png"
      |    },
      |    { "name": "🤔", "count": 1, "me": true }
      |  ],
      |  "reactions_allowed": false
      |}""".stripMargin

  private val announcementsJson: String = s"[$announcementJson]"

  private def recording(
      response: Response
  ): IO[(Ref[IO, List[Request]], Announcements[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Announcements.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("list targets the announcements path with the user token and decodes every nested entity") {
    for
      pair      <- recording(Response(HttpStatus.Ok, announcementsJson))
      (ref, api) = pair
      result    <- api.list()
      requests  <- ref.get
    yield
      val announcement = result.head
      assertEquals(announcement.id.value, "8")
      assertEquals(announcement.read, Some(true))
      assertEquals(announcement.reactionsAllowed, false)
      assertEquals(announcement.mentions.map(_.acct), List("alice"))
      assertEquals(announcement.statuses.map(_.id.value), List("42"))
      assertEquals(announcement.tags.map(_.name), List("server"))
      assertEquals(announcement.emojis.map(_.shortcode), List("blobaww"))
      assertEquals(announcement.emojis.head.category, Some("Blobs"))
      assertEquals(announcement.reactions.map(_.name), List("bongoCat", "🤔"))
      assertEquals(announcement.reactions.head.staticUrl, Some("https://files.code.party/bongo_static.png"))
      assertEquals(announcement.reactions(1).me, true)
      assertEquals(announcement.reactions(1).url, None)
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/announcements")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("dismiss POSTs the announcement path and accepts the empty object answer") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.dismiss(Id("8"))
      requests  <- ref.get
    yield
      assertEquals(result, ())
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/announcements/8/dismiss")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(request.body, None)
  }

  test("addReaction PUTs the shortcode as one path segment") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.addReaction(Id("8"), "bongoCat")
      requests  <- ref.get
    yield
      assertEquals(result, ())
      val request = requests.head
      assertEquals(request.method, HttpMethod.PUT)
      assertEquals(request.url, "https://code.party/api/v1/announcements/8/reactions/bongoCat")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("removeReaction DELETEs the path and percent-encodes a Unicode emoji name") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.removeReaction(Id("8"), "🤔")
      requests  <- ref.get
    yield
      assertEquals(result, ())
      val request = requests.head
      assertEquals(request.method, HttpMethod.DELETE)
      assertEquals(
        request.url,
        "https://code.party/api/v1/announcements/8/reactions/%F0%9F%A4%94"
      )
  }

  test("a 400 when reactions are disabled is an Http error and not retryable") {
    val body = """{"error":"Emoji reactions are currently not supported on this server"}"""
    for
      pair      <- recording(Response(HttpStatus(400), body))
      (_, api)   = pair
      result    <- api.addReaction(Id("8"), "🤔").attempt
    yield
      val error = apiError(result)
      assertEquals(
        error,
        ApiError.Http(
          HttpStatus(400),
          Some("Emoji reactions are currently not supported on this server"),
          body
        )
      )
      assertEquals(error.retryable, false)
  }

  test("a 422 for an unrecognised emoji is an Http error") {
    val body = """{"error":"Validation failed: Name is not a recognized emoji"}"""
    for
      pair      <- recording(Response(HttpStatus(422), body))
      (_, api)   = pair
      result    <- api.addReaction(Id("8"), "not-an-emoji").attempt
    yield
      val error = apiError(result)
      assert(!error.retryable, s"expected non-retryable, got $error")
      assertEquals(error, ApiError.Http(HttpStatus(422), Some("Validation failed: Name is not a recognized emoji"), body))
  }

  test("a 404 on dismiss is a structured, non-retryable Http error") {
    for
      pair      <- recording(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, api)   = pair
      result    <- api.dismiss(Id("8")).attempt
    yield
      val error = apiError(result)
      assert(error.isNotFound, s"expected isNotFound, got $error")
      assertEquals(error.retryable, false)
  }

  test("a 429 becomes RateLimited with Retry-After") {
    for
      pair <- recording(
                Response(HttpStatus(429), """{"error":"Too many requests"}""", Map("Retry-After" -> "5"))
              )
      (_, api) = pair
      result   <- api.list().attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(5.seconds), Some("Too many requests"))
      )
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair      <- recording(Response(HttpStatus.Ok, """{"id":"8"}"""))
      (_, api)   = pair
      result    <- api.list().attempt
    yield
      val error = apiError(result)
      assert(error.isInstanceOf[ApiError.Decode], s"got $error")
      assertEquals(error.retryable, false)
  }

  test("an anonymous client fails list without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, announcementsJson))
                )
      api    <- IO.pure(Announcements.live[IO](EndpointRunner.anonymous(backend, host)))
      result <- api.list().attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/announcements"))
      assertEquals(sent, 0)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair      <- recording(Response(HttpStatus.Ok, announcementsJson))
      (ref, api) = pair
      result    <- api.mapK(identity).list().attempt
      requests  <- ref.get
    yield
      assertEquals(result.map(_.map(_.id.value)), Right(List("8")))
      assertEquals(requests.map(_.url), List("https://code.party/api/v1/announcements"))
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
  }
