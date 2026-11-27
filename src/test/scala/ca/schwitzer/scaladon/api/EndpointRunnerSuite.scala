package ca.schwitzer.scaladon.api

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class EndpointRunnerSuite extends CatsEffectSuite:

  private val host  = Host("code.party")
  private val token = BearerToken("secret")

  private def recording(response: Response): IO[(Ref[IO, List[Request]], EndpointRunner[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> EndpointRunner(backend, host, Some(token))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("an empty 200 body satisfies a unit endpoint") {
    for
      pair     <- recording(Response(HttpStatus.Ok, ""))
      (ref, runner) = pair
      _        <- runner.run(Endpoint.unit(HttpMethod.DELETE, "/api/v1/thing/1"))
      requests <- ref.get
    yield
      assertEquals(requests.head.method, HttpMethod.DELETE)
      assertEquals(requests.head.url, "https://code.party/api/v1/thing/1")
  }

  test("a 204 No Content satisfies a unit endpoint") {
    for
      pair     <- recording(Response(HttpStatus(204), ""))
      (_, runner) = pair
      result   <- runner.run(Endpoint.unit(HttpMethod.DELETE, "/api/v1/thing/1")).attempt
    yield assertEquals(result, Right(()))
  }

  test("an empty success body is still a Decode failure for a typed endpoint") {
    for
      pair     <- recording(Response(HttpStatus.Ok, ""))
      (_, runner) = pair
      result   <- runner.run(Endpoint.get[String]("/api/v1/thing", authenticated = false)).attempt
    yield apiError(result) match
      case ApiError.Decode(_) => ()
      case other              => fail(s"expected Decode, got $other")
  }

  test("a non-JSON error body keeps the raw body and has no detail") {
    for
      pair     <- recording(Response(HttpStatus(500), "<html>oops</html>"))
      (_, runner) = pair
      result   <- runner.run(Endpoint.get[String]("/api/v1/thing", authenticated = false)).attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.Http(HttpStatus(500), None, "<html>oops</html>"))
      assertEquals(error.retryable, true)
  }

  test("a 429 without Retry-After is still RateLimited") {
    for
      pair     <- recording(Response(HttpStatus(429), """{"error":"slow down"}"""))
      (_, runner) = pair
      result   <- runner.run(Endpoint.get[String]("/api/v1/thing", authenticated = false)).attempt
    yield
      assertEquals(apiError(result), ApiError.RateLimited(None, Some("slow down")))
  }

  test("an HTTP-date Retry-After is ignored, not mis-parsed as seconds") {
    for
      pair <- recording(
                Response(
                  HttpStatus(429),
                  """{"error":"slow down"}""",
                  Map("Retry-After" -> "Wed, 21 Oct 2026 07:28:00 GMT")
                )
              )
      (_, runner) = pair
      result <- runner.run(Endpoint.get[String]("/api/v1/thing", authenticated = false)).attempt
    yield
      assertEquals(apiError(result), ApiError.RateLimited(None, Some("slow down")))
  }

  test("query values are encoded and repeated keys keep their order") {
    val endpoint = Endpoint.get[String](
      "/api/v1/thing",
      query = List("q" -> "a b&c", "id[]" -> "1", "id[]" -> "2"),
      authenticated = false
    )
    for
      pair     <- recording(Response(HttpStatus.Ok, "\"ok\""))
      (ref, runner) = pair
      result   <- runner.run(endpoint)
      requests <- ref.get
    yield
      assertEquals(result, "ok")
      assertEquals(
        requests.head.url,
        "https://code.party/api/v1/thing?q=a+b%26c&id%5B%5D=1&id%5B%5D=2"
      )
  }

  test("a public endpoint never carries a token, even when one is configured") {
    for
      pair     <- recording(Response(HttpStatus.Ok, "\"ok\""))
      (ref, runner) = pair
      _        <- runner.run(Endpoint.get[String]("/api/v1/thing", authenticated = false))
      requests <- ref.get
    yield assert(!requests.head.headers.contains("Authorization"))
  }

  test("an authenticated endpoint carries the bearer token") {
    for
      pair     <- recording(Response(HttpStatus.Ok, "\"ok\""))
      (ref, runner) = pair
      _        <- runner.run(Endpoint.get[String]("/api/v1/thing"))
      requests <- ref.get
    yield assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("an anonymous runner fails before calling the backend") {
    for
      ref <- Ref.of[IO, List[Request]](Nil)
      backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(Response(HttpStatus.Ok, "{}")))
      runner = EndpointRunner.anonymous(backend, host)
      result <- runner.run(Endpoint.get[String]("/api/v1/thing")).attempt
      sent   <- ref.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/thing"))
      assertEquals(sent, Nil)
  }

  test("JSON and form bodies reach the request unchanged") {
    val jsonBody = io.circe.Json.obj("status" -> io.circe.Json.fromString("hi"))
    for
      pair1 <- recording(Response(HttpStatus.Ok, "\"ok\""))
      (ref1, runner1) = pair1
      _ <- runner1.run(Endpoint.post[String]("/api/v1/thing", jsonBody))
      jsonRequests <- ref1.get

      pair2 <- recording(Response(HttpStatus.Ok, "\"ok\""))
      (ref2, runner2) = pair2
      _ <- runner2.run(Endpoint.postForm[String]("/api/v1/thing", List("a" -> "b")))
      formRequests <- ref2.get
    yield
      assertEquals(jsonRequests.head.body, Some(jsonBody))
      assertEquals(jsonRequests.head.form, None)
      assertEquals(formRequests.head.body, None)
      assertEquals(formRequests.head.form, Some(List("a" -> "b")))
  }

  test("runWithResponse exposes headers without changing the decoded value") {
    val response = Response(HttpStatus.Ok, "\"ok\"", Map("X-Thing" -> "yes"))
    for
      pair     <- recording(response)
      (_, runner) = pair
      value    <- runner.runWithResponse(Endpoint.get[String]("/api/v1/thing", authenticated = false))
    yield
      assertEquals(value._1, "ok")
      assertEquals(value._2.header("x-thing"), Some("yes"))
  }
