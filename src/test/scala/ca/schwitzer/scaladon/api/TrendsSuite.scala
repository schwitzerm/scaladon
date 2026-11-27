package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, PreviewCardType}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class TrendsSuite extends CatsEffectSuite:

  private val host  = Host("code.party")
  private val token = BearerToken("secret")

  private val tagsJson: String =
    """[
      |  {
      |    "id": "201",
      |    "name": "hola",
      |    "url": "https://code.party/tags/hola",
      |    "history": [
      |      { "day": "1574726400", "uses": "13", "accounts": "10" }
      |    ]
      |  }
      |]""".stripMargin

  private val statusesJson: String =
    """[
      |  {
      |    "id": "108910940413327534",
      |    "uri": "tag:code.party,2022-08-30:objectId=1",
      |    "url": "https://code.party/@alice/108910940413327534",
      |    "account": {
      |      "id": "7",
      |      "username": "alice",
      |      "acct": "alice",
      |      "display_name": "Alice",
      |      "note": "",
      |      "url": "https://code.party/@alice",
      |      "avatar": "https://code.party/a.png",
      |      "header": "https://code.party/h.png",
      |      "locked": false,
      |      "created_at": "2017-04-05T12:00:00.000Z"
      |    },
      |    "content": "<p>Trending</p>",
      |    "created_at": "2022-08-30T08:44:26.366Z",
      |    "visibility": "public",
      |    "in_reply_to_id": null,
      |    "in_reply_to_account_id": null,
      |    "spoiler_text": "",
      |    "sensitive": false,
      |    "reblog": null,
      |    "reblogs_count": 0,
      |    "favourites_count": 0,
      |    "favourited": false,
      |    "reblogged": false
      |  }
      |]""".stripMargin

  private val linksJson: String =
    """[
      |  {
      |    "url": "https://example.com/vote",
      |    "title": "Plan Your Vote",
      |    "description": "Everything you need to know",
      |    "type": "link",
      |    "author_name": "NBC News",
      |    "author_url": "",
      |    "provider_name": "NBC News",
      |    "provider_url": "",
      |    "html": "",
      |    "width": 400,
      |    "height": 225,
      |    "image": "https://files.code.party/card.jpeg",
      |    "embed_url": "",
      |    "blurhash": "UcQmF#ay~qofj[WBj[j[",
      |    "history": [
      |      { "day": "1661817600", "accounts": "7", "uses": "7" }
      |    ]
      |  }
      |]""".stripMargin

  private def recording(
      response: Response
  ): IO[(Ref[IO, List[Request]], Trends[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Trends.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("tags targets /api/v1/trends/tags anonymously and decodes history counts") {
    for
      pair      <- recording(Response(HttpStatus.Ok, tagsJson))
      (ref, api) = pair
      result    <- api.tags(limit = Some(20), offset = Some(40))
      requests  <- ref.get
    yield
      val tag = result.head
      assertEquals(tag.name, "hola")
      assertEquals(tag.history.map(_.uses), List(13))
      assertEquals(tag.history.map(_.accounts), List(10))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/trends/tags?limit=20&offset=40")
      assert(!request.headers.contains("Authorization"), "public trends must not carry a token")
  }

  test("statuses targets /api/v1/trends/statuses and builds no query string when unpaginated") {
    for
      pair      <- recording(Response(HttpStatus.Ok, statusesJson))
      (ref, api) = pair
      result    <- api.statuses()
      requests  <- ref.get
    yield
      assertEquals(result.map(_.id.value), List("108910940413327534"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/trends/statuses")
      assert(!request.headers.contains("Authorization"), "public trends must not carry a token")
  }

  test("links targets /api/v1/trends/links and decodes the preview card fields plus history") {
    for
      pair      <- recording(Response(HttpStatus.Ok, linksJson))
      (ref, api) = pair
      result    <- api.links(offset = Some(10))
      requests  <- ref.get
    yield
      val link = result.head
      assertEquals(link.url, "https://example.com/vote")
      assertEquals(link.cardType, PreviewCardType.Link)
      assertEquals(link.authorName, "NBC News")
      assertEquals(link.image, Some("https://files.code.party/card.jpeg"))
      assertEquals(link.history.map(_.uses), List(7))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/trends/links?offset=10")
      assert(!request.headers.contains("Authorization"), "public trends must not carry a token")
  }

  test("a 404 when trends are disabled is a structured, non-retryable Http error") {
    for
      pair    <- recording(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, api) = pair
      result  <- api.tags().attempt
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
      result   <- api.statuses().attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(5.seconds), Some("Too many requests"))
      )
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair    <- recording(Response(HttpStatus.Ok, """{"tags":[]}"""))
      (_, api) = pair
      result  <- api.tags().attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("an empty result decodes to an empty list") {
    for
      pair    <- recording(Response(HttpStatus.Ok, "[]"))
      (_, api) = pair
      result  <- api.links()
    yield assertEquals(result, Nil)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair      <- recording(Response(HttpStatus.Ok, tagsJson))
      (ref, api) = pair
      result    <- api.mapK(identity).tags().attempt
      requests  <- ref.get
    yield
      assertEquals(result.map(_.map(_.name)), Right(List("hola")))
      assertEquals(requests.map(_.url), List("https://code.party/api/v1/trends/tags"))
      assert(!requests.head.headers.contains("Authorization"))
  }
