package ca.schwitzer.scaladon.api

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.time.Instant
import java.util.concurrent.CancellationException
import scala.concurrent.duration.*

import ca.schwitzer.scaladon.Mastodon
import ca.schwitzer.scaladon.domain.{
  ApiError,
  ApiException,
  AsyncRefresh,
  HttpStatus,
  Id,
  MediaType,
  QuoteApprovalPolicy,
  Status,
  StatusPostResult,
  Visibility
}
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

  private val accountsJson: String =
    """[
      |  {
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
      |  }
      |]""".stripMargin

  private val contextJson: String =
    s"""{"ancestors": [$statusJson], "descendants": [$statusJson]}"""

  private val scheduledJson: String =
    """{
      |  "id": "3221",
      |  "scheduled_at": "2019-12-05T12:33:01.000Z",
      |  "params": {
      |    "text": "test content",
      |    "media_ids": null,
      |    "sensitive": null,
      |    "spoiler_text": null,
      |    "visibility": null,
      |    "language": null,
      |    "scheduled_at": null,
      |    "poll": null,
      |    "idempotency": null,
      |    "in_reply_to_id": null,
      |    "application_id": 596551,
      |    "quoted_status_id": "42",
      |    "quote_approval_policy": "followers"
      |  },
      |  "media_attachments": []
      |}""".stripMargin

  private val cardJson: String =
    """{
      |  "url": "https://example.com/a",
      |  "title": "An article",
      |  "description": "About things",
      |  "type": "link",
      |  "author_name": "",
      |  "author_url": "",
      |  "provider_name": "Example",
      |  "provider_url": "https://example.com",
      |  "html": "",
      |  "width": 400,
      |  "height": 200,
      |  "image": null,
      |  "embed_url": "",
      |  "blurhash": null,
      |  "missing_attribution": false
      |}""".stripMargin

  private val statusWithCardJson: String =
    statusJson.replace("\"reblog\": null,", s"\"reblog\": null,\n  \"card\": $cardJson,")

  /** The `DELETE /api/v1/statuses/:id` answer: the deleted status plus its source `text` and poll
    * (2.9.0 delete-and-redraft). `poll` is not modelled yet, so decoding must ignore it.
    */
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
      |  "in_reply_to_id": null,
      |  "in_reply_to_account_id": null,
      |  "spoiler_text": "",
      |  "sensitive": false,
      |  "reblog": null,
      |  "reblogs_count": 0,
      |  "favourites_count": 0,
      |  "favourited": false,
      |  "reblogged": false,
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
      |  "text": "hello world",
      |  "poll": {
      |    "id": "9",
      |    "expires_at": "2019-12-06T08:26:45.945Z",
      |    "expired": false,
      |    "multiple": false,
      |    "votes_count": 1,
      |    "voters_count": 1,
      |    "voted": true,
      |    "own_votes": [],
      |    "options": [{"title": "test 1", "votes_count": 1}],
      |    "emojis": []
      |  }
      |}""".stripMargin

  private def recordingBackend(response: Response): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  private def client(response: Response): IO[(Ref[IO, List[Request]], Mastodon[IO])] =
    recordingBackend(response).map { case (ref, http) => ref -> Mastodon.live(http, host, token) }

  private def statuses(response: Response): IO[(Ref[IO, List[Request]], Statuses[IO])] =
    recordingBackend(response).map { case (ref, http) =>
      ref -> Statuses.live[IO](EndpointRunner(http, host, Some(token)))
    }

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

  test("mapK identity on the Statuses algebra preserves requests") {
    val identity = new cats.~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair     <- statuses(Response(HttpStatus.Ok, statusJson))
      (ref, statuses) = pair
      result   <- statuses.mapK(identity).fetch(Id("42"))
      requests <- ref.get
    yield
      assertEquals(result.id.value, "42")
      assertEquals(requests.head.url, "https://code.party/api/v1/statuses/42")
  }

  test("post sends the 5.0 authoring fields when present") {
    for
      pair     <- statuses(Response(HttpStatus.Ok, statusJson))
      (ref, statuses) = pair
      _        <- statuses.post(
                    "hello",
                    Visibility.Public,
                    sensitive = Some(true),
                    language = Some("en"),
                    mediaIds = Some(List(Id("1"), Id("2"))),
                    quotedStatusId = Some(Id("42")),
                    quoteApprovalPolicy = Some(QuoteApprovalPolicy.Followers),
                    scheduledAt = Some(Instant.parse("2030-01-01T00:00:00Z"))
                  )
      requests <- ref.get
    yield
      val body = requests.head.body.flatMap(_.asObject).get
      assertEquals(body("sensitive").flatMap(_.asBoolean), Some(true))
      assertEquals(body("language").flatMap(_.asString), Some("en"))
      assertEquals(
        body("media_ids").flatMap(_.asArray).map(_.flatMap(_.asString)),
        Some(Vector("1", "2"))
      )
      assertEquals(body("quoted_status_id").flatMap(_.asString), Some("42"))
      assertEquals(body("quote_approval_policy").flatMap(_.asString), Some("followers"))
      assertEquals(body("scheduled_at").flatMap(_.asString), Some("2030-01-01T00:00:00Z"))
  }

  test("post omits every absent 5.0 option, never null") {
    for
      pair     <- statuses(Response(HttpStatus.Ok, statusJson))
      (ref, statuses) = pair
      _        <- statuses.post("hello")
      requests <- ref.get
    yield
      val body = requests.head.body.flatMap(_.asObject).get
      assertEquals(body("status").flatMap(_.asString), Some("hello"))
      assertEquals(body("visibility").flatMap(_.asString), Some("public"))
      List(
        "in_reply_to_id",
        "spoiler_text",
        "sensitive",
        "language",
        "media_ids",
        "quoted_status_id",
        "quote_approval_policy",
        "scheduled_at"
      ).foreach(key => assert(!body.contains(key), s"$key must be omitted"))
  }

  test("post decodes a status response as Posted") {
    for
      pair   <- statuses(Response(HttpStatus.Ok, statusJson))
      result <- pair._2.post("hello")
    yield result match
      case StatusPostResult.Posted(status) => assertEquals(status.id.value, "42")
      case other                           => fail(s"expected Posted, got $other")
  }

  test("post decodes a scheduled response as Scheduled") {
    for
      pair   <- statuses(Response(HttpStatus.Ok, scheduledJson))
      result <- pair._2.post("later", scheduledAt = Some(Instant.parse("2019-12-05T12:33:01Z")))
    yield result match
      case StatusPostResult.Scheduled(value) =>
        assertEquals(value.id.value, "3221")
        assertEquals(value.scheduledAt, Instant.parse("2019-12-05T12:33:01Z"))
        assertEquals(value.params.text, "test content")
        assertEquals(value.params.quotedStatusId, Some(Id("42")))
        assertEquals(value.params.quoteApprovalPolicy, Some(QuoteApprovalPolicy.Followers))
      case other => fail(s"expected Scheduled, got $other")
  }

  test("edit uses PUT and sends only the provided fields") {
    for
      pair     <- statuses(Response(HttpStatus.Ok, statusJson))
      (ref, statuses) = pair
      _        <- statuses.edit(
                    Id("42"),
                    text = Some("edited"),
                    spoilerText = Some("cw"),
                    quoteApprovalPolicy = Some(QuoteApprovalPolicy.Public)
                  )
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.PUT)
      assertEquals(request.url, "https://code.party/api/v1/statuses/42")
      val body = request.body.flatMap(_.asObject).get
      assertEquals(body("status").flatMap(_.asString), Some("edited"))
      assertEquals(body("spoiler_text").flatMap(_.asString), Some("cw"))
      assertEquals(body("quote_approval_policy").flatMap(_.asString), Some("public"))
      assert(!body.contains("sensitive"), "absent sensitive must be omitted")
      assert(!body.contains("media_ids"), "absent media_ids must be omitted")
  }

  test("delete returns the redraft status and sends no body") {
    for
      pair     <- statuses(Response(HttpStatus.Ok, redraftJson))
      (ref, statuses) = pair
      deleted  <- statuses.delete(Id("42"))
      requests <- ref.get
    yield
      assertEquals(deleted.id.value, "42")
      assertEquals(deleted.text, Some("hello world"))
      assertEquals(deleted.content, "<p>Hello</p>")
      assertEquals(deleted.mediaAttachments.map(_.id.value), List("223457"))
      assertEquals(deleted.mediaAttachments.map(_.kind), List(MediaType.Image))
      assertEquals(
        deleted.mediaAttachments.flatMap(_.url),
        List("https://code.party/media/a.png")
      )
      val request = requests.head
      assertEquals(request.method, HttpMethod.DELETE)
      assertEquals(request.url, "https://code.party/api/v1/statuses/42")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(request.body, None)
  }

  test("context returns the tree and the Mastodon-Async-Refresh token") {
    val response = Response(
      HttpStatus.Ok,
      contextJson,
      Map("Mastodon-Async-Refresh" -> """id="abc123", retry=5, result_count=2""")
    )
    for
      pair     <- statuses(response)
      (ref, statuses) = pair
      result   <- statuses.context(Id("42"))
      requests <- ref.get
    yield
      val (context, refresh) = result
      assertEquals(context.ancestors.map(_.id.value), List("42"))
      assertEquals(context.descendants.map(_.id.value), List("42"))
      assertEquals(refresh, Some(AsyncRefresh("abc123", 5, Some(2))))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/statuses/42/context")
      assert(!request.headers.contains("Authorization"), "context is public for public statuses")
  }

  test("context without the async header returns None") {
    for
      pair   <- statuses(Response(HttpStatus.Ok, contextJson))
      result <- pair._2.context(Id("42"))
    yield assertEquals(result._2, None)
  }

  test("favouritedBy targets the accounts path with the limit query") {
    for
      pair     <- statuses(Response(HttpStatus.Ok, accountsJson))
      (ref, statuses) = pair
      accounts <- statuses.favouritedBy(Id("42"), limit = Some(5))
      requests <- ref.get
    yield
      assertEquals(accounts.map(_.id.value), List("7"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/statuses/42/favourited_by?limit=5")
      assert(!request.headers.contains("Authorization"), "public endpoint must not carry a token")
  }

  test("rebloggedBy omits the query when no limit is given") {
    for
      pair     <- statuses(Response(HttpStatus.Ok, accountsJson))
      (ref, statuses) = pair
      accounts <- statuses.rebloggedBy(Id("42"))
      requests <- ref.get
    yield
      assertEquals(accounts.map(_.displayName), List("Alice"))
      assertEquals(requests.head.url, "https://code.party/api/v1/statuses/42/reblogged_by")
  }

  test("favourite, unfavourite, reblog and unreblog hit their POST paths") {
    for
      pair     <- statuses(Response(HttpStatus.Ok, statusJson))
      (ref, statuses) = pair
      _        <- statuses.favourite(Id("42"))
      _        <- statuses.unfavourite(Id("42"))
      _        <- statuses.reblog(Id("42"), Some(Visibility.Private))
      _        <- statuses.unreblog(Id("42"))
      requests <- ref.get
    yield
      assertEquals(
        requests.map(request => request.method -> request.url),
        List(
          HttpMethod.POST -> "https://code.party/api/v1/statuses/42/favourite",
          HttpMethod.POST -> "https://code.party/api/v1/statuses/42/unfavourite",
          HttpMethod.POST -> "https://code.party/api/v1/statuses/42/reblog",
          HttpMethod.POST -> "https://code.party/api/v1/statuses/42/unreblog"
        )
      )
      assert(requests.forall(_.headers.get("Authorization").contains("Bearer secret")))
      assertEquals(requests(2).form, Some(List("visibility" -> "private")))
      assertEquals(requests(0).form, Some(Nil))
  }

  test("card reads the preview card off the fetched status") {
    for
      pair     <- statuses(Response(HttpStatus.Ok, statusWithCardJson))
      (ref, statuses) = pair
      card     <- statuses.card(Id("42"))
      requests <- ref.get
    yield
      assertEquals(card.map(_.title), Some("An article"))
      assertEquals(card.map(_.cardType.wire), Some("link"))
      assertEquals(requests.head.url, "https://code.party/api/v1/statuses/42")
  }

  test("an anonymous client cannot favourite and never touches the network") {
    for
      calls <- Ref.of[IO, Int](0)
      http = HttpBackend.of[IO](_ => calls.update(_ + 1).as(Response(HttpStatus.Ok, statusJson)))
      anonymous = EndpointRunner.anonymous(http, host)
      result <- Statuses.live[IO](anonymous).favourite(Id("42")).attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/statuses/42/favourite"))
      assertEquals(sent, 0)
  }

  test("a malformed context body becomes a Decode error") {
    for
      pair   <- statuses(Response(HttpStatus.Ok, """{"ancestors":"nope"}"""))
      result <- pair._2.context(Id("42")).attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got $result")
  }

  test("parseAsyncRefresh reads id, retry and result_count") {
    assertEquals(
      Statuses.parseAsyncRefresh("""id="abc", retry=5, result_count=3"""),
      Some(AsyncRefresh("abc", 5, Some(3)))
    )
  }

  test("parseAsyncRefresh accepts an omitted result_count") {
    assertEquals(
      Statuses.parseAsyncRefresh("""id="abc", retry=5"""),
      Some(AsyncRefresh("abc", 5, None))
    )
  }

  test("parseAsyncRefresh rejects a missing id or retry") {
    assertEquals(Statuses.parseAsyncRefresh("""retry=5"""), None)
    assertEquals(Statuses.parseAsyncRefresh("""id="abc"""), None)
    assertEquals(Statuses.parseAsyncRefresh("""id="abc", retry=soon"""), None)
  }

  test("parseAsyncRefresh tolerates surrounding whitespace and an unquoted id") {
    assertEquals(
      Statuses.parseAsyncRefresh("  id=abc ,  retry= 7 , result_count= 1 "),
      Some(AsyncRefresh("abc", 7, Some(1)))
    )
  }
