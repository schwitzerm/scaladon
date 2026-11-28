package ca.schwitzer.scaladon.http

import cats.Show
import cats.effect.IO
import munit.CatsEffectSuite

import ca.schwitzer.scaladon.domain.{ApiError, HttpStatus}
import ca.schwitzer.scaladon.instances.given

class TransportValuesSuite extends CatsEffectSuite:

  test("Host normalises the scheme and the trailing slash") {
    assertEquals(Host("code.party").baseUrl, "https://code.party")
    assertEquals(Host("code.party/").baseUrl, "https://code.party")
    assertEquals(Host("https://code.party/").baseUrl, "https://code.party")
    assertEquals(Host("http://code.party").baseUrl, "http://code.party")
  }

  test("BearerToken redacts its value in Show") {
    val token = BearerToken("super-secret")
    val shown = Show[BearerToken].show(token)
    assertEquals(shown, "BearerToken(**redacted**)")
    assert(!shown.contains("super-secret"))
  }

  test("Response.header is case-insensitive and keeps the original value") {
    val response = Response(HttpStatus.Ok, "{}", Map("Retry-After" -> "5"))
    assertEquals(response.header("retry-after"), Some("5"))
    assertEquals(response.header("RETRY-AFTER"), Some("5"))
    assertEquals(response.header("missing"), None)
  }

  test("Response.json reports a Decode error instead of throwing") {
    Response(HttpStatus.Ok, "not json").json match
      case Left(ApiError.Decode(_)) => ()
      case other                    => fail(s"expected Decode, got $other")

    assertEquals(Response(HttpStatus.Ok, """{"a":1}""").json.flatMap(_.hcursor.get[Int]("a")), Right(1))
  }

  test("HttpStatus treats only 2xx as success") {
    assertEquals(HttpStatus(199).isSuccess, false)
    assertEquals(HttpStatus(200).isSuccess, true)
    assertEquals(HttpStatus(299).isSuccess, true)
    assertEquals(HttpStatus(300).isSuccess, false)
  }

  test("HttpBackend.const answers every request with the same response") {
    val response = Response(HttpStatus.Created, "{}")
    val backend  = HttpBackend.const[IO](response)
    backend.run(Request(HttpMethod.GET, "https://code.party/a")).map(assertEquals(_, response))
  }
