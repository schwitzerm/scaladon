package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{
  ApiError,
  ApiException,
  AsyncRefreshState,
  AsyncRefreshStatus,
  HttpStatus
}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class AsyncRefreshesSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val refreshId =
    "ImNvbnRleHQ6MTEzNjQwNTczMzAzNzg1MTc4OnJlZnJlc2gi--c526259eb4a1f3ef0d4b91cf8c99bf501330a815"

  private def wrapped(status: String, resultCount: Option[Int]): String =
    val count = resultCount.fold("null")(_.toString)
    s"""{"async_refresh":{"id":"$refreshId","status":"$status","result_count":$count}}"""

  private def recording(response: Response): IO[(Ref[IO, List[Request]], AsyncRefreshes[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> AsyncRefreshes.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("get polls the experimental path with a token and decodes a running refresh") {
    for
      pair      <- recording(Response(HttpStatus.Ok, wrapped("running", Some(2))))
      (ref, api) = pair
      result    <- api.get(refreshId)
      requests  <- ref.get
    yield
      assertEquals(result, AsyncRefreshStatus(refreshId, AsyncRefreshState.Running, Some(2)))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, s"https://code.party/api/v1_alpha/async_refreshes/$refreshId")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(request.body, None)
  }

  test("a finished refresh without a result_count decodes as zero unknown") {
    for
      pair    <- recording(Response(HttpStatus.Ok, wrapped("finished", None)))
      (_, api) = pair
      result  <- api.get(refreshId)
    yield
      assertEquals(result.status, AsyncRefreshState.Finished)
      assertEquals(result.resultCount, None)
  }

  test("an unknown status fails decoding instead of leaking a string") {
    for
      pair    <- recording(Response(HttpStatus.Ok, wrapped("paused", Some(1))))
      (_, api) = pair
      result  <- api.get(refreshId).attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("a payload without the async_refresh wrapper fails decoding") {
    val unwrapped = s"""{"id":"$refreshId","status":"running"}"""
    for
      pair    <- recording(Response(HttpStatus.Ok, unwrapped))
      (_, api) = pair
      result  <- api.get(refreshId).attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("a 404 raises a structured, non-retryable Http error") {
    for
      pair    <- recording(Response(HttpStatus.NotFound, """{"error":"Not Found"}"""))
      (_, api) = pair
      result  <- api.get(refreshId).attempt
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
      result   <- api.get(refreshId).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(5.seconds), Some("Too many requests"))
      )
  }

  test("an anonymous client fails without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, wrapped("finished", None)))
                )
      api    = AsyncRefreshes.live[IO](EndpointRunner.anonymous(backend, host))
      result <- api.get(refreshId).attempt
      sent   <- calls.get
    yield
      assertEquals(
        apiError(result),
        ApiError.NotAuthenticated(s"/api/v1_alpha/async_refreshes/$refreshId")
      )
      assertEquals(sent, 0)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair       <- recording(Response(HttpStatus.Ok, wrapped("running", Some(3))))
      (_, api)   = pair
      translated = api.mapK(identity)
      result     <- translated.get(refreshId).attempt
    yield
      assertEquals(
        result.map(_.status),
        Right(AsyncRefreshState.Running)
      )
  }
