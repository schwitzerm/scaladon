package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, SearchType}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class SearchSuite extends CatsEffectSuite:

  private val host  = Host("code.party")
  private val token = BearerToken("secret")

  private val accountJson: String =
    """{
      |  "id": "180744",
      |  "username": "catstar",
      |  "acct": "catstar@catgram.jp",
      |  "display_name": "catstar",
      |  "note": "",
      |  "url": "https://catgram.jp/@catstar",
      |  "avatar": "https://catgram.jp/a.png",
      |  "header": "https://catgram.jp/h.png",
      |  "locked": false,
      |  "created_at": "2017-04-05T12:00:00.000Z"
      |}""".stripMargin

  private val statusJson: String =
    """{
      |  "id": "103085519055545958",
      |  "uri": "tag:code.party,2019-11-05:objectId=103085519055545958",
      |  "url": "https://code.party/@alice/103085519055545958",
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
      |  "content": "<p>cats</p>",
      |  "created_at": "2019-11-05T13:23:09.000Z",
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

  private val tagJson: String =
    """{
      |  "id": "205",
      |  "name": "cats",
      |  "url": "https://code.party/tags/cats",
      |  "history": [
      |    {"day": "1574553600", "uses": "10", "accounts": "9"}
      |  ],
      |  "following": null
      |}""".stripMargin

  private val resultsJson: String =
    s"""{"accounts":[$accountJson],"statuses":[$statusJson],"hashtags":[$tagJson]}"""

  private def recordingBackend(response: Response): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  private def client(response: Response): IO[(Ref[IO, List[Request]], Search[IO])] =
    recordingBackend(response).map { case (ref, http) =>
      ref -> Search.live[IO](EndpointRunner(http, host, Some(token)))
    }

  private def anonymousClient(
      response: Response
  ): IO[(Ref[IO, List[Request]], Search[IO])] =
    recordingBackend(response).map { case (ref, http) =>
      ref -> Search.live[IO](EndpointRunner.anonymous(http, host))
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

  test("a plain search is public, sends only q and decodes every category") {
    for
      pair     <- client(Response(HttpStatus.Ok, resultsJson))
      (ref, search) = pair
      result   <- search.search("cats").attempt
      requests <- ref.get
    yield
      val results = result.fold(error => fail(s"unexpected error $error"), identity)
      assertEquals(results.accounts.map(_.id.value), List("180744"))
      assertEquals(results.statuses.map(_.id.value), List("103085519055545958"))
      assertEquals(results.hashtags.map(_.name), List("cats"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v2/search?q=cats")
      assert(!request.headers.contains("Authorization"), "a plain search is public")
  }

  test("q is percent-encoded and a type filter is sent") {
    for
      pair     <- client(Response(HttpStatus.Ok, resultsJson))
      (ref, search) = pair
      _        <- search.search("café cats", kind = Some(SearchType.Hashtags))
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(
        request.url,
        "https://code.party/api/v2/search?q=caf%C3%A9+cats&type=hashtags"
      )
      assertEquals(params(request).get("q"), Some(List("café cats")))
      assert(!request.headers.contains("Authorization"), "a type filter alone is public")
  }

  test("search encodes q, type, resolve, following, offset and limit in the documented order") {
    for
      pair     <- client(Response(HttpStatus.Ok, resultsJson))
      (ref, search) = pair
      _        <- search.search(
                    "cats",
                    kind = Some(SearchType.Accounts),
                    resolve = true,
                    following = true,
                    offset = Some(20),
                    limit = Some(5)
                  )
      requests <- ref.get
    yield
      assertEquals(
        requests.head.url,
        "https://code.party/api/v2/search?q=cats&type=accounts&resolve=true&following=true&offset=20&limit=5"
      )
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("offset without resolve still carries a token") {
    for
      pair     <- client(Response(HttpStatus.Ok, resultsJson))
      (ref, search) = pair
      _        <- search.search("cats", kind = Some(SearchType.Statuses), offset = Some(40))
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(
        request.url,
        "https://code.party/api/v2/search?q=cats&type=statuses&offset=40"
      )
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("an unset following flag and offset are omitted") {
    for
      pair     <- client(Response(HttpStatus.Ok, resultsJson))
      (ref, search) = pair
      _        <- search.search("cats", limit = Some(2))
      requests <- ref.get
    yield
      val query = params(requests.head)
      assertEquals(query.get("q"), Some(List("cats")))
      assertEquals(query.get("limit"), Some(List("2")))
      assert(!query.contains("following"))
      assert(!query.contains("offset"))
      assert(!query.contains("type"))
      assert(!query.contains("resolve"))
  }

  test("an anonymous client cannot use resolve and fails without touching the network") {
    for
      pair     <- anonymousClient(Response(HttpStatus.Ok, resultsJson))
      (ref, search) = pair
      result   <- search.search("cats", resolve = true).attempt
      requests <- ref.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v2/search"))
      assertEquals(requests, Nil)
  }

  test("an anonymous client cannot use offset") {
    for
      pair     <- anonymousClient(Response(HttpStatus.Ok, resultsJson))
      (ref, search) = pair
      result   <- search.search("cats", offset = Some(20)).attempt
      requests <- ref.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v2/search"))
      assertEquals(requests, Nil)
  }

  test("a 401 from search is an Http error") {
    val body = """{"error":"The access token is invalid"}"""
    for
      pair     <- client(Response(HttpStatus.Unauthorized, body))
      search = pair._2
      result   <- search.search("cats", resolve = true).attempt
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
      search = pair._2
      result   <- search.search("cats").attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.RateLimited(Some(5.seconds), Some("Too many requests")))
      assertEquals(error.retryable, true)
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair     <- client(Response(HttpStatus.Ok, """{"accounts":"not-an-array"}"""))
      search = pair._2
      result   <- search.search("cats", kind = Some(SearchType.Statuses)).attempt
    yield
      val error = apiError(result)
      assert(error.isInstanceOf[ApiError.Decode], s"got $error")
      assertEquals(error.retryable, false)
  }

  test("mapK translates the search algebra between effects") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair     <- client(Response(HttpStatus.Ok, resultsJson))
      (ref, search) = pair
      result   <- search.mapK(identity).search("cats").attempt
      requests <- ref.get
    yield
      assertEquals(result.map(_.hashtags.map(_.name)), Right(List("cats")))
      assertEquals(requests.map(_.url), List("https://code.party/api/v2/search?q=cats"))
      assert(!requests.head.headers.contains("Authorization"))
  }
