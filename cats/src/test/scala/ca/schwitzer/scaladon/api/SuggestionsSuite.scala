package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id, SuggestionSource}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class SuggestionsSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val accountJson: String =
    """{
      |  "id": "109031732217496096",
      |  "username": "alice",
      |  "acct": "alice",
      |  "display_name": "Alice",
      |  "note": "",
      |  "url": "https://code.party/@alice",
      |  "avatar": "https://code.party/a.png",
      |  "header": "https://code.party/h.png",
      |  "locked": false,
      |  "created_at": "2026-02-25T11:35:01.394Z"
      |}""".stripMargin

  private val suggestionsJson: String =
    s"""[
       |  {
       |    "source": "staff",
       |    "sources": ["featured"],
       |    "account": $accountJson
       |  },
       |  {
       |    "source": "global",
       |    "sources": ["most_followed", "most_interactions"],
       |    "account": ${accountJson.replace("109031732217496096", "109031732217496097")}
       |  }
       |]""".stripMargin

  private def recording(response: Response): IO[(Ref[IO, List[Request]], Suggestions[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Suggestions.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("list sends the limit and decodes every source of each suggestion") {
    for
      pair      <- recording(Response(HttpStatus.Ok, suggestionsJson))
      (ref, api) = pair
      result    <- api.list(limit = Some(10))
      requests  <- ref.get
    yield
      assertEquals(result.map(_.account.username), List("alice", "alice"))
      assertEquals(result.head.sources, List(SuggestionSource.Featured))
      assertEquals(
        result(1).sources,
        List(SuggestionSource.MostFollowed, SuggestionSource.MostInteractions)
      )
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v2/suggestions?limit=10")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("list without a limit sends no empty query string") {
    for
      pair      <- recording(Response(HttpStatus.Ok, suggestionsJson))
      (ref, api) = pair
      _         <- api.list()
      requests  <- ref.get
    yield assertEquals(requests.head.url, "https://code.party/api/v2/suggestions")
  }

  test("remove deletes the suggestion by account id") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.remove(Id("109031732217496096")).attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      val request = requests.head
      assertEquals(request.method, HttpMethod.DELETE)
      assertEquals(request.url, "https://code.party/api/v1/suggestions/109031732217496096")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("a 404 raises a structured, non-retryable Http error") {
    for
      pair    <- recording(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, api) = pair
      result  <- api.list().attempt
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
      result   <- api.remove(Id("42")).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(5.seconds), Some("Too many requests"))
      )
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair    <- recording(Response(HttpStatus.Ok, """{"not":"a list"}"""))
      (_, api) = pair
      result  <- api.list().attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("an anonymous client fails without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, suggestionsJson))
                )
      api    <- IO.pure(Suggestions.live[IO](EndpointRunner.anonymous(backend, host)))
      result <- api.list().attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v2/suggestions"))
      assertEquals(sent, 0)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair      <- recording(Response(HttpStatus.Ok, suggestionsJson))
      (_, api)   = pair
      translated = api.mapK(identity)
      result    <- translated.list().attempt
    yield assertEquals(result.map(_.map(_.sources)), Right(List(
      List(SuggestionSource.Featured),
      List(SuggestionSource.MostFollowed, SuggestionSource.MostInteractions)
    )))
  }
