package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class FavouritesSuite extends CatsEffectSuite:

  private val host  = Host("code.party")
  private val token = BearerToken("secret")

  private def statusJson(id: String): String =
    s"""{
       |  "id": "$id",
       |  "uri": "https://code.party/users/haskal/statuses/$id",
       |  "url": "https://code.party/@haskal/$id",
       |  "account": {
       |    "id": "297420",
       |    "username": "haskal",
       |    "acct": "haskal@code.party",
       |    "display_name": "haskal",
       |    "note": "",
       |    "url": "https://code.party/@haskal",
       |    "avatar": "https://code.party/a.png",
       |    "header": "https://code.party/h.png",
       |    "locked": false,
       |    "created_at": "2017-04-05T12:00:00.000Z"
       |  },
       |  "content": "<p>the pirate gay</p>",
       |  "created_at": "2019-11-23T07:35:52.000Z",
       |  "visibility": "unlisted",
       |  "in_reply_to_id": null,
       |  "in_reply_to_account_id": null,
       |  "spoiler_text": "",
       |  "sensitive": false,
       |  "reblog": null,
       |  "reblogs_count": 1,
       |  "favourites_count": 1,
       |  "favourited": true,
       |  "reblogged": false
       |}""".stripMargin

  private val statusesJson: String = s"[${statusJson("103186075217296344")}]"

  private def recordingBackend(response: Response): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  private def client(response: Response): IO[(Ref[IO, List[Request]], Favourites[IO])] =
    recordingBackend(response).map { case (ref, http) =>
      ref -> Favourites.live[IO](EndpointRunner(http, host, Some(token)))
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

  test("list targets the favourites path with the bearer token and decodes statuses") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, favourites) = pair
      result   <- favourites.list().attempt
      requests <- ref.get
    yield
      assertEquals(result.map(_.map(_.id.value)), Right(List("103186075217296344")))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/favourites")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("list encodes pagination cursors in the documented order") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, favourites) = pair
      _        <- favourites.list(
                    maxId = Some(Id("23716836")),
                    sinceId = Some(Id("100")),
                    minId = Some(Id("23716978")),
                    limit = Some(2)
                  )
      requests <- ref.get
    yield
      assertEquals(
        requests.head.url,
        "https://code.party/api/v1/favourites?max_id=23716836&since_id=100&min_id=23716978&limit=2"
      )
      assertEquals(
        params(requests.head).get("max_id"),
        Some(List("23716836"))
      )
  }

  test("min_id alone pages forward without the other cursors") {
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, favourites) = pair
      _        <- favourites.list(minId = Some(Id("23716978")))
      requests <- ref.get
    yield assertEquals(
      requests.head.url,
      "https://code.party/api/v1/favourites?min_id=23716978"
    )
  }

  test("an empty favourites list decodes to Nil") {
    for
      pair     <- client(Response(HttpStatus.Ok, "[]"))
      favourites = pair._2
      result   <- favourites.list()
    yield assertEquals(result, Nil)
  }

  test("an anonymous client fails without touching the network") {
    for
      callCount <- Ref.of[IO, Int](0)
      http = HttpBackend.of[IO](_ =>
               callCount.update(_ + 1).as(Response(HttpStatus.Ok, statusesJson))
             )
      anonymous = Favourites.live[IO](EndpointRunner.anonymous(http, host))
      result    <- anonymous.list().attempt
      sent      <- callCount.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/favourites"))
      assertEquals(sent, 0)
  }

  test("a 401 from the server is an Http error") {
    val body = """{"error":"The access token is invalid"}"""
    for
      pair     <- client(Response(HttpStatus.Unauthorized, body))
      favourites = pair._2
      result   <- favourites.list().attempt
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
      favourites = pair._2
      result   <- favourites.list().attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.RateLimited(Some(5.seconds), Some("Too many requests")))
      assertEquals(error.retryable, true)
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair     <- client(Response(HttpStatus.Ok, """{"id":"42"}"""))
      favourites = pair._2
      result   <- favourites.list().attempt
    yield
      val error = apiError(result)
      assert(error.isInstanceOf[ApiError.Decode], s"got $error")
      assertEquals(error.retryable, false)
  }

  test("mapK translates the favourites algebra between effects") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair     <- client(Response(HttpStatus.Ok, statusesJson))
      (ref, favourites) = pair
      result   <- favourites.mapK(identity).list().attempt
      requests <- ref.get
    yield
      assertEquals(result.map(_.map(_.id.value)), Right(List("103186075217296344")))
      assertEquals(requests.map(_.url), List("https://code.party/api/v1/favourites"))
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
  }
