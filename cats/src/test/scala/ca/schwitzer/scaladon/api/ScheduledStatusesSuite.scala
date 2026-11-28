package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.time.Instant
import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id, MediaType}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class ScheduledStatusesSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val scheduledJson: String =
    """{
      |  "id": "3221",
      |  "scheduled_at": "2019-12-05T12:33:01.000Z",
      |  "params": {
      |    "poll": null,
      |    "text": "test content",
      |    "media_ids": null,
      |    "sensitive": null,
      |    "visibility": "public",
      |    "idempotency": null,
      |    "scheduled_at": null,
      |    "spoiler_text": "",
      |    "application_id": 596551,
      |    "in_reply_to_id": null,
      |    "quoted_status_id": null,
      |    "quote_approval_policy": "followers",
      |    "with_rate_limit": false
      |  },
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
      |  ]
      |}""".stripMargin

  private val rescheduledJson: String =
    scheduledJson.replace("2019-12-05T12:33:01.000Z", "2030-01-01T00:00:00.000Z")

  private def recording(response: Response): IO[(Ref[IO, List[Request]], ScheduledStatuses[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> ScheduledStatuses.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("list sends the documented pagination query in order and decodes the statuses") {
    val payload = s"""[$scheduledJson,$scheduledJson]"""
    for
      pair      <- recording(Response(HttpStatus.Ok, payload))
      (ref, api) = pair
      result    <- api.list(
                     maxId = Some(Id("40")),
                     sinceId = Some(Id("20")),
                     minId = Some(Id("30")),
                     limit = Some(5)
                   )
      requests  <- ref.get
    yield
      assertEquals(result.map(_.id.value), List("3221", "3221"))
      assertEquals(result.head.scheduledAt, Instant.parse("2019-12-05T12:33:01.000Z"))
      assertEquals(result.head.params.text, "test content")
      assertEquals(result.head.params.spoilerText, None)
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(
        request.url,
        "https://code.party/api/v1/scheduled_statuses?max_id=40&since_id=20&min_id=30&limit=5"
      )
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("list without cursors sends no query string") {
    for
      pair      <- recording(Response(HttpStatus.Ok, s"[$scheduledJson]"))
      (ref, api) = pair
      _         <- api.list()
      requests  <- ref.get
    yield assertEquals(requests.head.url, "https://code.party/api/v1/scheduled_statuses")
  }

  test("get targets the scheduled status path and decodes its media attachments") {
    for
      pair      <- recording(Response(HttpStatus.Ok, scheduledJson))
      (ref, api) = pair
      result    <- api.get(Id("3221"))
      requests  <- ref.get
    yield
      assertEquals(result.id.value, "3221")
      assertEquals(result.mediaAttachments.map(_.id.value), List("223457"))
      assertEquals(result.mediaAttachments.map(_.kind), List(MediaType.Image))
      assertEquals(result.mediaAttachments.head.description, Some("a diagram"))
      assertEquals(result.mediaAttachments.head.url, Some("https://code.party/media/a.png"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/scheduled_statuses/3221")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("an absent media_attachments array normalises to an empty list") {
    val withoutAttachments =
      scheduledJson.replace(""""media_attachments": [""", """"attachments_removed": [""")
    for
      pair      <- recording(Response(HttpStatus.Ok, withoutAttachments))
      (_, api)   = pair
      result    <- api.get(Id("3221"))
    yield assertEquals(result.mediaAttachments, Nil)
  }

  test("reschedule PUTs scheduled_at as form data, never a JSON body") {
    val at = Instant.parse("2030-01-01T00:00:00Z")
    for
      pair      <- recording(Response(HttpStatus.Ok, rescheduledJson))
      (ref, api) = pair
      result    <- api.reschedule(Id("3221"), at)
      requests  <- ref.get
    yield
      assertEquals(result.scheduledAt, at)
      val request = requests.head
      assertEquals(request.method, HttpMethod.PUT)
      assertEquals(request.url, "https://code.party/api/v1/scheduled_statuses/3221")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(request.form, Some(List("scheduled_at" -> "2030-01-01T00:00:00Z")))
      assertEquals(request.body, None)
  }

  test("cancel DELETEs with no body and accepts the empty success response") {
    for
      pair      <- recording(Response(HttpStatus.Ok, ""))
      (ref, api) = pair
      result    <- api.cancel(Id("3221")).attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      val request = requests.head
      assertEquals(request.method, HttpMethod.DELETE)
      assertEquals(request.url, "https://code.party/api/v1/scheduled_statuses/3221")
      assertEquals(request.body, None)
      assertEquals(request.form, None)
  }

  test("a 404 raises a structured, non-retryable Http error") {
    for
      pair      <- recording(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, api)   = pair
      result    <- api.get(Id("3221")).attempt
    yield
      val error = apiError(result)
      assert(error.isNotFound, s"expected isNotFound, got $error")
      assertEquals(error.retryable, false)
  }

  test("a 401 raises an unauthorized Http error") {
    for
      pair      <- recording(Response(HttpStatus.Unauthorized, """{"error":"The access token is invalid"}"""))
      (_, api)   = pair
      result    <- api.list().attempt
    yield
      val error = apiError(result)
      assert(error.isUnauthorized, s"expected isUnauthorized, got $error")
      assertEquals(error.retryable, false)
  }

  test("a 429 becomes RateLimited with Retry-After") {
    for
      pair <- recording(
                Response(HttpStatus(429), """{"error":"Too many requests"}""", Map("Retry-After" -> "5"))
              )
      (_, api) = pair
      result   <- api.cancel(Id("3221")).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(5.seconds), Some("Too many requests"))
      )
  }

  test("a 422 on reschedule surfaces the server's validation message") {
    for
      pair <- recording(
                Response(
                  HttpStatus(422),
                  """{"error":"Validation failed: Scheduled at The scheduled date must be in the future"}"""
                )
              )
      (_, api) = pair
      result   <- api.reschedule(Id("3221"), Instant.parse("2020-01-01T00:00:00Z")).attempt
    yield
      apiError(result) match
        case ApiError.Http(status, detail, _) =>
          assertEquals(status.code, 422)
          assert(detail.exists(_.contains("must be in the future")), s"got $detail")
        case other => fail(s"expected Http(422), got $other")
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair      <- recording(Response(HttpStatus.Ok, """[{"id":"3221"}]"""))
      (_, api)   = pair
      result    <- api.list().attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("an anonymous client fails authenticated calls without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, s"[$scheduledJson]"))
                )
      api    <- IO.pure(ScheduledStatuses.live[IO](EndpointRunner.anonymous(backend, host)))
      result <- api.list().attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/scheduled_statuses"))
      assertEquals(sent, 0)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair      <- recording(Response(HttpStatus.Ok, s"[$scheduledJson]"))
      (_, api)   = pair
      translated = api.mapK(identity)
      result    <- translated.list(limit = Some(1)).attempt
    yield assertEquals(result.map(_.map(_.id.value)), Right(List("3221")))
  }
