package ca.schwitzer.scaladon

import cats.{Eq, Order, Show}
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import ca.schwitzer.scaladon.api.{Endpoint, EndpointRunner, attemptApi}
import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{ApiError, HttpStatus, Id}
import ca.schwitzer.scaladon.http.{
  BearerToken,
  Host,
  HttpBackend,
  HttpMethod,
  Request,
  Response
}
import ca.schwitzer.scaladon.instances.given

/** The documented Cats entry points, exercised as a user would use them. */
class PublicSurfaceSuite extends CatsEffectSuite:

  private val host  = Host("code.party")
  private val token = BearerToken("secret")

  private val userInfoJson: String =
    """{
      |  "iss": "https://code.party",
      |  "sub": "https://code.party/users/alice",
      |  "name": "Alice",
      |  "preferred_username": "alice",
      |  "profile": "https://code.party/@alice",
      |  "picture": "https://code.party/a.png"
      |}""".stripMargin

  private def recording(response: Response): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  test("Mastodon.live sends the bearer token and decodes an authenticated response") {
    for
      pair     <- recording(Response(HttpStatus.Ok, userInfoJson))
      (ref, backend) = pair
      client   = Mastodon.live(backend, host, token)
      info     <- client.oauth.userinfo
      requests <- ref.get
    yield
      assertEquals(info.preferredUsername, "alice")
      assertEquals(info.subject, "https://code.party/users/alice")
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("Mastodon.anonymous fails an authenticated endpoint with NotAuthenticated") {
    val unused = HttpBackend.of[IO](_ => IO.raiseError(new AssertionError("backend must not run")))
    Mastodon.anonymous(unused, host).oauth.userinfo.attemptApi.map { result =>
      assertEquals(result, Left(ApiError.NotAuthenticated("/oauth/userinfo")))
    }
  }

  test("HttpBackend.const and Endpoint.get decode an Id") {
    val backend = HttpBackend.const[IO](Response(HttpStatus.Ok, "\"42\""))
    val runner  = EndpointRunner(backend, host, Some(token))
    runner.run(Endpoint.get[Id]("/any")).map(id => assertEquals(id, Id("42")))
  }

  test("Endpoint.putForm keeps the form constructor") {
    val endpoint = Endpoint.putForm[String]("/x", List("a" -> "b"))
    assertEquals(endpoint.method, HttpMethod.PUT)
    assertEquals(endpoint.form, Some(List("a" -> "b")))
    assertEquals(endpoint.body, None)
    assertEquals(endpoint.authenticated, true)
  }

  test("Request rendering redacts credentials without changing storage") {
    val request = Request(
      HttpMethod.POST,
      "https://code.party/oauth/revoke",
      Map("Authorization" -> "Bearer secret"),
      form = Some(List("client_secret" -> "hidden", "token" -> "ACCESS"))
    )
    val rendered = request.toString
    assert(rendered.contains("Authorization=**redacted**"), rendered)
    assert(rendered.contains("client_secret=**redacted**"), rendered)
    assert(rendered.contains("token=**redacted**"), rendered)
    assert(!rendered.contains("hidden"), rendered)
    assert(!rendered.contains("ACCESS"), rendered)
    assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("the instances import provides Eq, Order and Show") {
    assert(Eq[Id].eqv(Id("7"), Id("7")))
    assert(Order[Id].lt(Id("10"), Id("2")))
    assertEquals(Show[Id].show(Id("42")), "42")
    assertEquals(
      Show[BearerToken].show(BearerToken("secret")),
      "BearerToken(**redacted**)"
    )
  }
