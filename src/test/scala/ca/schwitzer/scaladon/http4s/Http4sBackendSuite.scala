package ca.schwitzer.scaladon.http4s

import cats.effect.{IO, Ref}
import cats.syntax.applicative.*
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Response as Http4sResponse, Status}
import org.http4s.client.Client
import org.typelevel.ci.CIString

import ca.schwitzer.scaladon.Mastodon
import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id}
import ca.schwitzer.scaladon.http.{
  BearerToken,
  Host,
  HttpBackend,
  HttpMethod,
  Multipart,
  MultipartPart,
  Request
}

class Http4sBackendSuite extends CatsEffectSuite:

  private final case class Seen(request: org.http4s.Request[IO], body: String)

  /** A real http4s `Client` (via `fromHttpApp`) that records the request it receives. */
  private def recordingBackend(
      status: Status,
      responseBody: String,
      responseHeaders: List[(String, String)] = Nil
  ): IO[(Ref[IO, List[Seen]], HttpBackend[IO])] =
    Ref.of[IO, List[Seen]](Nil).map { ref =>
      val app = HttpApp[IO] { request =>
        request.as[String].flatMap { text =>
          val response = Http4sResponse[IO](status)
            .withEntity(responseBody)
            .putHeaders(responseHeaders.map((name, value) => org.http4s.Header.Raw(CIString(name), value))*)
          ref.update(_ :+ Seen(request, text)) *> response.pure[IO]
        }
      }
      ref -> Http4sBackend.fromClient(Client.fromHttpApp(app))
    }

  test("a POST is compiled to an http4s request with JSON body, content type and auth header") {
    for
      pair     <- recordingBackend(Status.Ok, """{"id":"42"}""")
      (ref, http) = pair
      response <- http.run(
                    Request(
                      HttpMethod.POST,
                      "https://code.party/api/v1/statuses",
                      Map("Authorization" -> "Bearer secret"),
                      Some(Json.obj("status" -> Json.fromString("hi")))
                    )
                  )
      seen <- ref.get
    yield
      assertEquals(response.status, HttpStatus.Ok)
      assertEquals(response.body, """{"id":"42"}""")
      val request = seen.head.request
      assertEquals(request.method, org.http4s.Method.POST)
      assertEquals(request.uri.renderString, "https://code.party/api/v1/statuses")
      assertEquals(
        request.headers.headers.find(_.name == CIString("Authorization")).map(_.value),
        Some("Bearer secret")
      )
      assertEquals(request.contentType.map(_.mediaType), Some(MediaType.application.json))
      assertEquals(
        io.circe.parser.parse(seen.head.body).flatMap(_.hcursor.get[String]("status")),
        Right("hi")
      )
  }

  test("a GET without a body is sent with an empty entity and no JSON content type") {
    for
      pair     <- recordingBackend(Status.Ok, "[]")
      (ref, http) = pair
      _        <- http.run(Request(HttpMethod.GET, "https://code.party/api/v1/instance"))
      seen     <- ref.get
    yield
      val request = seen.head.request
      assertEquals(request.method, org.http4s.Method.GET)
      assertEquals(seen.head.body, "")
      assertEquals(request.contentType, None)
  }

  test("a non-success status and its body are preserved for the API layer") {
    for
      pair     <- recordingBackend(Status.NotFound, """{"error":"Record not found"}""")
      (_, http) = pair
      response <- http.run(Request(HttpMethod.GET, "https://code.party/api/v1/statuses/1"))
    yield
      assertEquals(response.status, HttpStatus.NotFound)
      assertEquals(response.body, """{"error":"Record not found"}""")
  }

  test("an unparseable URL fails the effect before reaching the client") {
    for
      pair     <- recordingBackend(Status.Ok, "{}")
      (ref, http) = pair
      result   <- http.run(Request(HttpMethod.GET, "not a url")).attempt
      seen     <- ref.get
    yield
      assert(result.isLeft, s"expected a failure, got $result")
      assertEquals(seen, Nil)
  }

  test("the client runs end to end over the http4s interpreter") {
    for
      pair     <- recordingBackend(Status.NotFound, """{"error":"Record not found"}""")
      (_, http) = pair
      client    = Mastodon.live(http, Host("code.party"), BearerToken("secret"))
      result   <- client.statuses.fetch(Id("42")).attempt
    yield result match
      case Left(ApiException(ApiError.Http(status, message, _))) =>
        assertEquals(status, HttpStatus.NotFound)
        assertEquals(message, Some("Record not found"))
      case other => fail(s"expected ApiException(Http), got $other")
  }

  test("rate-limit headers survive the interpreter and become RateLimited") {
    for
      pair     <- recordingBackend(Status.TooManyRequests, """{"error":"Too many requests"}""", List("Retry-After" -> "5"))
      (_, http) = pair
      response <- http.run(Request(HttpMethod.GET, "https://code.party/api/v1/statuses/1"))
      client    = Mastodon.live(http, Host("code.party"), BearerToken("secret"))
      result   <- client.statuses.fetch(Id("1")).attempt
    yield
      assertEquals(response.header("Retry-After"), Some("5"))
      result match
        case Left(ApiException(ApiError.RateLimited(retryAfter, message))) =>
          assertEquals(retryAfter, Some(5.seconds))
          assertEquals(message, Some("Too many requests"))
        case other => fail(s"expected ApiException(RateLimited), got $other")
  }

  test("a form body is sent as application/x-www-form-urlencoded") {
    for
      pair     <- recordingBackend(Status.Ok, "{}")
      (ref, http) = pair
      _ <- http.run(
             Request(
               HttpMethod.POST,
               "https://code.party/oauth/token",
               form = Some(List("grant_type" -> "authorization_code", "scope" -> "read write"))
             )
           )
      seen <- ref.get
    yield
      val request = seen.head.request
      assertEquals(
        request.contentType.map(_.mediaType),
        Some(MediaType.application.`x-www-form-urlencoded`)
      )
      assert(seen.head.body.contains("grant_type=authorization_code"), seen.head.body)
      assert(seen.head.body.contains("scope=read"), seen.head.body)
  }

  test("a multipart body is sent as multipart/form-data with a boundary and file part") {
    val file = MultipartPart.file(
      "file",
      "cat.png",
      "image/png",
      "PNGDATA".getBytes(StandardCharsets.UTF_8)
    )
    for
      pair <- recordingBackend(Status.Accepted, """{"id":"1"}""")
      (ref, http) = pair
      _ <- http.run(
             Request(
               HttpMethod.POST,
               "https://code.party/api/v2/media",
               multipart = Some(Multipart(List(file)))
             )
           )
      seen <- ref.get
    yield
      val request = seen.head.request
      assertEquals(request.contentType.map(_.mediaType.mainType), Some("multipart"))
      assertEquals(request.contentType.map(_.mediaType.subType), Some("form-data"))
      val boundary = request.contentType.flatMap(_.mediaType.extensions.get("boundary"))
      assert(boundary.nonEmpty, s"missing boundary in ${request.contentType}")
      val prefix = s"--${boundary.get}\r\n"
      assert(seen.head.body.startsWith(prefix), seen.head.body)
      assert(
        seen.head.body.contains(
          "Content-Disposition: form-data; name=\"file\"; filename=\"cat.png\"\r\n"
        ),
        seen.head.body
      )
      assert(seen.head.body.contains("Content-Type: image/png\r\n"), seen.head.body)
      assert(seen.head.body.contains("PNGDATA"), seen.head.body)
      assert(seen.head.body.endsWith(s"--${boundary.get}--\r\n"), seen.head.body)
  }

  test("PUT, PATCH and DELETE map to their http4s methods") {
    def methodOf(method: HttpMethod): IO[org.http4s.Method] =
      for
        pair     <- recordingBackend(Status.Ok, "{}")
        (ref, http) = pair
        _        <- http.run(Request(method, "https://code.party/x"))
        seen     <- ref.get
      yield seen.head.request.method

    for
      put    <- methodOf(HttpMethod.PUT)
      patch  <- methodOf(HttpMethod.PATCH)
      delete <- methodOf(HttpMethod.DELETE)
    yield
      assertEquals(put, org.http4s.Method.PUT)
      assertEquals(patch, org.http4s.Method.PATCH)
      assertEquals(delete, org.http4s.Method.DELETE)
  }

  test("response headers are stored lower-cased and stay case-insensitive") {
    for
      pair     <- recordingBackend(Status.Ok, "{}", List("X-RateLimit-Remaining" -> "7"))
      (_, http) = pair
      response <- http.run(Request(HttpMethod.GET, "https://code.party/x"))
    yield
      assertEquals(response.header("x-ratelimit-remaining"), Some("7"))
      assertEquals(response.header("X-RateLimit-Remaining"), Some("7"))
      assert(response.headers.contains("x-ratelimit-remaining"))
  }

  test("the bundled ember client resource can be built for IO") {
    MastodonHttp4s.resource[IO](Host("code.party"), BearerToken("secret")).use(_ => IO.unit)
  }
