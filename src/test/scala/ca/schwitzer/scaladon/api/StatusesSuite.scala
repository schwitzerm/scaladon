package ca.schwitzer.scaladon.api

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.util.concurrent.CancellationException
import scala.concurrent.duration.*

import ca.schwitzer.scaladon.Mastodon
import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id, Status, Visibility}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class StatusesSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

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
      |  "in_reply_to_id": null,
      |  "in_reply_to_account_id": null,
      |  "spoiler_text": "",
      |  "sensitive": false,
      |  "reblog": null,
      |  "reblogs_count": 0,
      |  "favourites_count": 0,
      |  "favourited": false,
      |  "reblogged": false
      |}""".stripMargin

  private def recordingBackend(response: Response): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  private def client(response: Response): IO[(Ref[IO, List[Request]], Mastodon[IO])] =
    recordingBackend(response).map { case (ref, http) => ref -> Mastodon.live(http, host, token) }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("fetch targets the public status path and sends no token") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusJson))
      (ref, mastodon) = pair
      result   <- mastodon.statuses.fetch(Id("42")).attempt
      requests <- ref.get
    yield
      assertEquals(result.map(_.id.value), Right("42"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/statuses/42")
      assert(!request.headers.contains("Authorization"), "public endpoint must not carry a token")
  }

  test("post sends a JSON body with absent options omitted, never null") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusJson))
      (ref, mastodon) = pair
      _        <- mastodon.statuses.post("hello", Visibility.Private)
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/statuses")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      val body = request.body.flatMap(_.asObject).get
      assertEquals(body("status").flatMap(_.asString), Some("hello"))
      assertEquals(body("visibility").flatMap(_.asString), Some("private"))
      assert(!body.contains("spoiler_text"), "absent spoiler_text must be omitted")
      assert(!body.contains("in_reply_to_id"), "absent in_reply_to_id must be omitted")
  }

  test("post includes options when they are present") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusJson))
      (ref, mastodon) = pair
      _        <- mastodon.statuses.post(
                    "reply",
                    Visibility.Direct,
                    inReplyToId = Some(Id("9")),
                    spoilerText = Some("cw")
                  )
      requests <- ref.get
    yield
      val body = requests.head.body.flatMap(_.asObject).get
      assertEquals(body("in_reply_to_id").flatMap(_.asString), Some("9"))
      assertEquals(body("spoiler_text").flatMap(_.asString), Some("cw"))
  }

  test("a 404 raises a structured, non-retryable Http error with the server message") {
    for
      pair   <- client(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      mastodon = pair._2
      result <- mastodon.statuses.fetch(Id("42")).attempt
    yield
      val error = apiError(result)
      assert(error.isNotFound, s"expected isNotFound, got $error")
      assertEquals(error.retryable, false)
      assertEquals(error, ApiError.Http(HttpStatus.NotFound, Some("Record not found"), """{"error":"Record not found"}"""))
      assertEquals(error.message, "HTTP 404: Record not found")
  }

  test("a 401 is identifiable as an authorization failure") {
    for
      pair   <- client(Response(HttpStatus.Unauthorized, """{"error":"The access token is invalid"}"""))
      mastodon = pair._2
      result <- mastodon.statuses.delete(Id("42")).attempt
    yield
      val error = apiError(result)
      assert(error.isUnauthorized, s"expected isUnauthorized, got $error")
      assertEquals(error.retryable, false)
  }

  test("a 422 keeps the validation message") {
    for
      pair   <- client(Response(HttpStatus(422), """{"error":"Validation failed: Text can't be blank"}"""))
      mastodon = pair._2
      result <- mastodon.statuses.post("").attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.Http(HttpStatus(422), Some("Validation failed: Text can't be blank"), """{"error":"Validation failed: Text can't be blank"}"""))
  }

  test("a 429 becomes RateLimited with Retry-After and is retryable") {
    val response = Response(
      HttpStatus(429),
      """{"error":"Too many requests"}""",
      Map("Retry-After" -> "5")
    )
    for
      pair   <- client(response)
      mastodon = pair._2
      result <- mastodon.statuses.fetch(Id("42")).attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.RateLimited(Some(5.seconds), Some("Too many requests")))
      assertEquals(error.retryable, true)
  }

  test("a 5xx is retryable") {
    for
      pair   <- client(Response(HttpStatus(503), ""))
      mastodon = pair._2
      result <- mastodon.statuses.fetch(Id("42")).attempt
    yield assertEquals(apiError(result).retryable, true)
  }

  test("a malformed success body becomes a non-retryable Decode error") {
    for
      pair   <- client(Response(HttpStatus.Ok, """{"id":"42"}"""))
      mastodon = pair._2
      result <- mastodon.statuses.fetch(Id("42")).attempt
    yield
      val error = apiError(result)
      assert(error.isInstanceOf[ApiError.Decode], s"got $error")
      assertEquals(error.retryable, false)
  }

  test("an anonymous client fails authenticated calls without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      http = HttpBackend.of[IO](_ => calls.update(_ + 1).as(Response(HttpStatus.Ok, statusJson)))
      anonymous = Mastodon.anonymous(http, host)
      result <- anonymous.statuses.delete(Id("42")).attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/statuses/42"))
      assertEquals(sent, 0)
  }

  test("a transport failure is wrapped as Transport and is retryable") {
    val boom = new RuntimeException("connection refused")
    val failing = HttpBackend.of[IO](_ => IO.raiseError(boom))
    Mastodon.live(failing, host, token).statuses.fetch(Id("1")).attempt.map { result =>
      apiError(result) match
        case ApiError.Transport(cause) =>
          assertEquals(cause, boom)
        case other => fail(s"expected Transport, got $other")
    }
  }

  test("cancellation is not turned into an API error") {
    val cancelled = new CancellationException("cancelled")
    val failing = HttpBackend.of[IO](_ => IO.raiseError(cancelled))
    Mastodon.live(failing, host, token).statuses.fetch(Id("1")).attempt.map { result =>
      assertEquals(ApiError.fromThrowable(result.left.getOrElse(fail("expected failure"))), None)
    }
  }

  test("attemptApi recovers API errors as values") {
    for
      pair <- client(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      mastodon = pair._2
      result <- mastodon.statuses.fetch(Id("42")).attemptApi
    yield assert(result.left.exists(_.isNotFound), s"got $result")
  }

  test("attemptApi leaves non-API throwables on the error channel") {
    val boom = new IllegalStateException("bug")
    IO.raiseError[Status](boom).attemptApi.attempt.map { result =>
      assertEquals(result, Left(boom))
    }
  }

  test("mapK translates a client between effects") {
    val identity = new cats.~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair   <- client(Response(HttpStatus.Ok, statusJson))
      mastodon = pair._2
      translated = mastodon.mapK(identity)
      result <- translated.statuses.fetch(Id("42")).attempt
    yield assertEquals(result.map(_.id.value), Right("42"))
  }
