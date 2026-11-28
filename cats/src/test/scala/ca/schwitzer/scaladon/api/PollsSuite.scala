package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class PollsSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val pollJson: String =
    """{
      |  "id": "34830",
      |  "expires_at": "2026-11-30T12:00:00.000Z",
      |  "expired": false,
      |  "multiple": true,
      |  "votes_count": 10,
      |  "voters_count": 7,
      |  "voted": true,
      |  "own_votes": [0, 2],
      |  "options": [
      |    { "title": "Cats", "votes_count": 6 },
      |    { "title": "Dogs", "votes_count": 3 },
      |    { "title": "Birds", "votes_count": 1 }
      |  ],
      |  "emojis": []
      |}""".stripMargin

  private def recording(response: Response): IO[(Ref[IO, List[Request]], Polls[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Polls.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("get targets the poll path without a token and decodes the poll") {
    for
      pair      <- recording(Response(HttpStatus.Ok, pollJson))
      (ref, api) = pair
      poll      <- api.get(Id("34830"))
      requests  <- ref.get
    yield
      assertEquals(poll.id.value, "34830")
      assertEquals(poll.multiple, true)
      assertEquals(poll.voted, Some(true))
      assertEquals(poll.ownVotes, Some(List(0, 2)))
      assertEquals(poll.options.map(_.title), List("Cats", "Dogs", "Birds"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/polls/34830")
      assertEquals(request.headers.get("Authorization"), None)
  }

  test("vote posts each choice as a repeated choices[] form field") {
    for
      pair      <- recording(Response(HttpStatus.Ok, pollJson))
      (ref, api) = pair
      poll      <- api.vote(Id("34830"), List(0, 2))
      requests  <- ref.get
    yield
      assertEquals(poll.ownVotes, Some(List(0, 2)))
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/polls/34830/votes")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(request.body, None)
      assertEquals(request.form, Some(List("choices[]" -> "0", "choices[]" -> "2")))
  }

  test("a 422 for an already-voted poll raises a structured Http error") {
    val body = """{"error":"Validation failed: You have already voted on this poll"}"""
    for
      pair    <- recording(Response(HttpStatus(422), body))
      (_, api) = pair
      result  <- api.vote(Id("34830"), List(0)).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.Http(
          HttpStatus(422),
          Some("Validation failed: You have already voted on this poll"),
          body
        )
      )
  }

  test("a 404 raises a structured, non-retryable Http error") {
    for
      pair    <- recording(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, api) = pair
      result  <- api.get(Id("42")).attempt
    yield
      val error = apiError(result)
      assert(error.isNotFound, s"expected isNotFound, got $error")
      assertEquals(error.retryable, false)
  }

  test("malformed poll json becomes a Decode error") {
    for
      pair    <- recording(Response(HttpStatus.Ok, """{"id":"34830"}"""))
      (_, api) = pair
      result  <- api.get(Id("34830")).attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("an anonymous client cannot vote and does not touch the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, pollJson))
                )
      api    <- IO.pure(Polls.live[IO](EndpointRunner.anonymous(backend, host)))
      result <- api.vote(Id("34830"), List(0)).attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/polls/34830/votes"))
      assertEquals(sent, 0)
  }

  test("a 429 becomes RateLimited with Retry-After") {
    for
      pair <- recording(
                Response(
                  HttpStatus(429),
                  """{"error":"Too many requests"}""",
                  Map("Retry-After" -> "5")
                )
              )
      (_, api) = pair
      result   <- api.vote(Id("34830"), List(0)).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(5.seconds), Some("Too many requests"))
      )
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair       <- recording(Response(HttpStatus.Ok, pollJson))
      (_, api)    = pair
      translated  = api.mapK(identity)
      result     <- translated.vote(Id("34830"), List(0, 2)).attempt
    yield assertEquals(result.map(_.id.value), Right("34830"))
  }
