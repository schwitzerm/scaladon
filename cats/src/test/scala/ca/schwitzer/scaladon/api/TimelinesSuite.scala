package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class TimelinesSuite extends CatsEffectSuite:

  private val host  = Host("code.party")
  private val token = BearerToken("secret")

  private def statusJson(id: String): String =
    s"""{
       |  "id": "$id",
       |  "uri": "tag:code.party,2017-04-05:objectId=$id",
       |  "url": "https://code.party/@alice/$id",
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

  private val statusesJson: String = s"[${statusJson("42")},${statusJson("41")}]"

  private def recordingBackend(response: Response): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  private def client(response: Response): IO[(Ref[IO, List[Request]], Timelines[IO])] =
    recordingBackend(response).map { case (ref, http) =>
      ref -> Timelines.live[IO](EndpointRunner(http, host, Some(token)))
    }

  private def params(request: Request): Map[String, List[String]] =
    request.url
      .split("\\?", 2)
      .lift(1)
      .toList
      .flatMap(_.split("&").toList)
      .map { pair =>
        val parts = pair.split("=", 2)
        val key   = URLDecoder.decode(parts(0), StandardCharsets.UTF_8)
        val value = URLDecoder.decode(parts.lift(1).getOrElse(""), StandardCharsets.UTF_8)
        key -> value
      }
      .groupMap(_._1)(_._2)

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("home targets the authenticated path and sends only the flags that are set") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, timelines) = pair
      result   <- timelines.home(excludeDirect = true, excludeQuotes = true).attempt
      requests <- ref.get
    yield
      assertEquals(result.map(_.map(_.id.value)), Right(List("42", "41")))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assert(request.url.startsWith("https://code.party/api/v1/timelines/home?"), request.url)
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      val query = params(request)
      assertEquals(query.get("exclude_direct"), Some(List("true")))
      assertEquals(query.get("exclude_quotes"), Some(List("true")))
      assert(!query.contains("exclude_reblogs"), "an unset flag must be omitted, not sent as false")
      assert(!query.contains("exclude_replies"), "an unset flag must be omitted, not sent as false")
  }

  test("home without filters sends no query string") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, timelines) = pair
      _        <- timelines.home()
      requests <- ref.get
    yield assertEquals(requests.head.url, "https://code.party/api/v1/timelines/home")
  }

  test("home encodes pagination cursors in the documented order") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, timelines) = pair
      _        <- timelines.home(
                    maxId = Some(Id("100")),
                    sinceId = Some(Id("50")),
                    minId = Some(Id("60")),
                    limit = Some(40)
                  )
      requests <- ref.get
    yield
      assertEquals(
        requests.head.url,
        "https://code.party/api/v1/timelines/home?max_id=100&since_id=50&min_id=60&limit=40"
      )
  }

  test("publicTimeline is anonymous and omits false booleans") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, timelines) = pair
      _        <- timelines.publicTimeline(local = true)
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assert(request.url.startsWith("https://code.party/api/v1/timelines/public?"), request.url)
      assert(!request.headers.contains("Authorization"), "public timeline must not carry a token")
      assertEquals(params(request).get("local"), Some(List("true")))
      assert(!params(request).contains("remote"))
      assert(!params(request).contains("only_media"))
  }

  test("publicTimeline sends remote, only_media and pagination") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, timelines) = pair
      _        <- timelines.publicTimeline(
                    remote = true,
                    onlyMedia = true,
                    maxId = Some(Id("90")),
                    limit = Some(5)
                  )
      requests <- ref.get
    yield
      val query = params(requests.head)
      assertEquals(query.get("remote"), Some(List("true")))
      assertEquals(query.get("only_media"), Some(List("true")))
      assertEquals(query.get("max_id"), Some(List("90")))
      assertEquals(query.get("limit"), Some(List("5")))
      assert(!query.contains("local"))
  }

  test("tag sends repeated any[], all[] and none[] parameters") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, timelines) = pair
      _        <- timelines.tag(
                    "cats",
                    any = List("caturday", "kitten"),
                    all = List("photography"),
                    none = List("dog"),
                    local = true,
                    onlyMedia = true,
                    limit = Some(20)
                  )
      requests <- ref.get
    yield
      val request = requests.head
      assert(!request.headers.contains("Authorization"), "hashtag timeline must not carry a token")
      assert(
        request.url.startsWith("https://code.party/api/v1/timelines/tag/cats?"),
        request.url
      )
      assert(request.url.contains("any%5B%5D=caturday&any%5B%5D=kitten"), request.url)
      val query = params(request)
      assertEquals(query.get("any[]"), Some(List("caturday", "kitten")))
      assertEquals(query.get("all[]"), Some(List("photography")))
      assertEquals(query.get("none[]"), Some(List("dog")))
      assertEquals(query.get("local"), Some(List("true")))
      assertEquals(query.get("only_media"), Some(List("true")))
      assertEquals(query.get("limit"), Some(List("20")))
      assert(!query.contains("remote"))
  }

  test("tag percent-encodes the hashtag as one path segment") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, timelines) = pair
      _        <- timelines.tag("Qualitätskatzen")
      requests <- ref.get
    yield
      assertEquals(
        requests.head.url,
        "https://code.party/api/v1/timelines/tag/Qualit%C3%A4tskatzen"
      )
  }

  test("list targets the list path and sends pagination") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, timelines) = pair
      _        <- timelines.list(Id("9"), maxId = Some(Id("77")), limit = Some(10))
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(
        request.url,
        "https://code.party/api/v1/timelines/list/9?max_id=77&limit=10"
      )
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("an empty timeline decodes to an empty list") {
    for
      pair     <- client(Response(HttpStatus.Ok, "[]"))
      timelines = pair._2
      result   <- timelines.home()
    yield assertEquals(result, Nil)
  }

  test("a 206 partial content still decodes the statuses received") {
    for
      pair     <- client(Response(HttpStatus(206), s"[${statusJson("42")}]"))
      timelines = pair._2
      result   <- timelines.home()
    yield assertEquals(result.map(_.id.value), List("42"))
  }

  test("a 401 from an unauthenticated timeline is an Http error") {
    val body = """{"error":"The access token is invalid"}"""
    for
      pair     <- client(Response(HttpStatus.Unauthorized, body))
      timelines = pair._2
      result   <- timelines.publicTimeline().attempt
    yield
      val error = apiError(result)
      assert(error.isUnauthorized, s"expected isUnauthorized, got $error")
      assertEquals(error, ApiError.Http(HttpStatus(401), Some("The access token is invalid"), body))
  }

  test("a 429 becomes RateLimited with Retry-After and is retryable") {
    val response = Response(
      HttpStatus(429),
      """{"error":"Too many requests"}""",
      Map("Retry-After" -> "5")
    )
    for
      pair     <- client(response)
      timelines = pair._2
      result   <- timelines.home().attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.RateLimited(Some(5.seconds), Some("Too many requests")))
      assertEquals(error.retryable, true)
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair     <- client(Response(HttpStatus.Ok, """{"id":"42"}"""))
      timelines = pair._2
      result   <- timelines.home().attempt
    yield
      val error = apiError(result)
      assert(error.isInstanceOf[ApiError.Decode], s"got $error")
      assertEquals(error.retryable, false)
  }

  test("an anonymous client fails home without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      http = HttpBackend.of[IO](_ => calls.update(_ + 1).as(Response(HttpStatus.Ok, statusesJson)))
      anonymous = Timelines.live[IO](EndpointRunner.anonymous(http, host))
      result <- anonymous.home().attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/timelines/home"))
      assertEquals(sent, 0)
  }

  test("mapK translates the timeline algebra between effects") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, timelines) = pair
      result   <- timelines.mapK(identity).home().attempt
      requests <- ref.get
    yield
      assertEquals(result.map(_.map(_.id.value)), Right(List("42", "41")))
      assertEquals(requests.map(_.url), List("https://code.party/api/v1/timelines/home"))
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
  }
