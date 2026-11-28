package ca.schwitzer.scaladon.api

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id}
import ca.schwitzer.scaladon.http.{
  BearerToken,
  Host,
  HttpBackend,
  HttpMethod,
  MultipartPart,
  Request,
  Response
}

class MediaSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val processedJson: String =
    """{
      |  "id": "22348641",
      |  "type": "image",
      |  "url": "https://files.mastodon.social/media_attachments/files/022/348/641/original/e96382f26c72a29c.jpeg",
      |  "preview_url": "https://files.mastodon.social/media_attachments/files/022/348/641/small/e96382f26c72a29c.jpeg",
      |  "remote_url": null,
      |  "text_url": "https://mastodon.social/media/4Zj6ewxzzzDi0g8JnZQ",
      |  "meta": {
      |    "focus": {"x": -0.42, "y": 0.69},
      |    "original": {"width": 640, "height": 480, "size": "640x480", "aspect": 1.3333333333333333}
      |  },
      |  "description": "test uploaded via api",
      |  "blurhash": "UFBWY:8_0Jxv4mx]t8t64.%M-:IUWGWAt6M}"
      |}""".stripMargin

  private val processingJson: String =
    """{
      |  "id": "22348641",
      |  "type": "image",
      |  "url": null,
      |  "preview_url": "https://files.mastodon.social/media_attachments/files/022/348/641/small/cebc6d51be03e509.jpeg",
      |  "remote_url": null,
      |  "text_url": "https://mastodon.social/media/4Zj6ewxzzzDi0g8JnZQ",
      |  "meta": {"focus": {"x": -0.69, "y": 0.42}},
      |  "description": "test uploaded via api",
      |  "blurhash": "UFBWY:8_0Jxv4mx]t8t64.%M-:IUWGWAt6M}"
      |}""".stripMargin

  private def recordingBackend(response: Response): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  private def media(
      response: Response,
      withToken: Boolean = true
  ): IO[(Ref[IO, List[Request]], Media[IO])] =
    recordingBackend(response).map { case (ref, http) =>
      ref -> Media.live[IO](EndpointRunner(http, host, if withToken then Some(token) else None))
    }

  private def bytes(value: String): Array[Byte] = value.getBytes(StandardCharsets.UTF_8)

  private def file(name: String, filename: String, contentType: String): MultipartPart =
    MultipartPart(name, Some(filename), Some(contentType), bytes(s"$filename bytes"))

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("upload sends the file and optional fields as multipart parts with a bearer token") {
    val thumbnail = file("ignored", "thumb.png", "image/png")
    for
      pair <- media(Response(HttpStatus.Ok, processedJson))
      (ref, media) = pair
      _ <- media.upload(
             file("ignored", "cat.png", "image/png"),
             thumbnail = Some(thumbnail),
             description = Some("a cat"),
             focus = Some("-0.5,0.25")
           )
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v2/media")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(request.body, None)
      assertEquals(request.form, None)

      val parts = request.multipart.map(_.parts).getOrElse(fail("expected a multipart body"))
      assertEquals(parts.map(_.name), List("file", "thumbnail", "description", "focus"))

      val uploaded = parts.head
      assertEquals(uploaded.filename, Some("cat.png"))
      assertEquals(uploaded.contentType, Some("image/png"))
      assertEquals(uploaded.bytes.toSeq, bytes("cat.png bytes").toSeq)

      assertEquals(parts(1).filename, Some("thumb.png"))
      assertEquals(parts(2).filename, None)
      assertEquals(new String(parts(2).bytes, StandardCharsets.UTF_8), "a cat")
      assertEquals(new String(parts(3).bytes, StandardCharsets.UTF_8), "-0.5,0.25")
  }

  test("a 202 upload still processing decodes with a null url") {
    for
      pair <- media(Response(HttpStatus(202), processingJson))
      (ref, media) = pair
      result   <- media.upload(file("file", "clip.mp4", "video/mp4"))
      requests <- ref.get
    yield
      assertEquals(result.id.value, "22348641")
      assertEquals(result.url, None)
      assertEquals(
        result.previewUrl,
        Some("https://files.mastodon.social/media_attachments/files/022/348/641/small/cebc6d51be03e509.jpeg")
      )
      assertEquals(result.description, Some("test uploaded via api"))
      assertEquals(
        requests.head.multipart.map(_.parts.map(_.name)),
        Some(List("file"))
      )
  }

  test("get targets the v1 media path and decodes the processed attachment") {
    for
      pair <- media(Response(HttpStatus.Ok, processedJson))
      (ref, media) = pair
      result   <- media.get(Id("22348641"))
      requests <- ref.get
    yield
      assertEquals(result.kind.wire, "image")
      assertEquals(
        result.url,
        Some("https://files.mastodon.social/media_attachments/files/022/348/641/original/e96382f26c72a29c.jpeg")
      )
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/media/22348641")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("a 206 partial-content get is a success with the url still absent") {
    for
      pair <- media(Response(HttpStatus(206), processingJson))
      (_, media) = pair
      result <- media.get(Id("22348641"))
    yield assertEquals(result.url, None)
  }

  test("update sends description, focus and thumbnail as a PUT multipart body") {
    for
      pair <- media(Response(HttpStatus.Ok, processedJson))
      (ref, media) = pair
      _ <- media.update(
             Id("22348641"),
             description = Some("updated alt text"),
             focus = Some("0.1,0.9"),
             thumbnail = Some(file("thumbnail", "custom.jpg", "image/jpeg"))
           )
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.PUT)
      assertEquals(request.url, "https://code.party/api/v1/media/22348641")
      val parts = request.multipart.map(_.parts).getOrElse(fail("expected a multipart body"))
      assertEquals(parts.map(_.name), List("thumbnail", "description", "focus"))
      assertEquals(parts.head.filename, Some("custom.jpg"))
      assertEquals(new String(parts(1).bytes, StandardCharsets.UTF_8), "updated alt text")
      assertEquals(new String(parts(2).bytes, StandardCharsets.UTF_8), "0.1,0.9")
  }

  test("delete targets the media path and accepts an empty success body") {
    for
      pair <- media(Response(HttpStatus.Ok, ""))
      (ref, media) = pair
      result   <- media.delete(Id("22348641"))
      requests <- ref.get
    yield
      assertEquals(result, ())
      assertEquals(requests.head.method, HttpMethod.DELETE)
      assertEquals(requests.head.url, "https://code.party/api/v1/media/22348641")
  }

  test("a 404 from get becomes ApiError.Http with the server message") {
    for
      pair <- media(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, media) = pair
      result <- media.get(Id("22348641")).attempt
    yield
      apiError(result) match
        case ApiError.Http(status, detail, _) =>
          assertEquals(status, HttpStatus.NotFound)
          assertEquals(detail, Some("Record not found"))
        case other => fail(s"expected Http, got $other")
  }

  test("a 429 upload becomes RateLimited with the Retry-After delay") {
    for
      pair <- media(
                Response(HttpStatus(429), """{"error":"Too many requests"}""", Map("retry-after" -> "5"))
              )
      (_, media) = pair
      result <- media.upload(file("file", "cat.png", "image/png")).attempt
    yield
      apiError(result) match
        case ApiError.RateLimited(retryAfter, message) =>
          assertEquals(retryAfter, Some(5.seconds))
          assertEquals(message, Some("Too many requests"))
        case other => fail(s"expected RateLimited, got $other")
  }

  test("a malformed success body becomes ApiError.Decode") {
    for
      pair <- media(Response(HttpStatus.Ok, "not json"))
      (_, media) = pair
      result <- media.get(Id("22348641")).attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], result.toString)
  }

  test("a missing token fails before the backend is called") {
    for
      pair <- media(Response(HttpStatus.Ok, processedJson), withToken = false)
      (ref, media) = pair
      result   <- media.upload(file("file", "cat.png", "image/png")).attempt
      requests <- ref.get
    yield
      apiError(result) match
        case ApiError.NotAuthenticated(path) => assertEquals(path, "/api/v2/media")
        case other                           => fail(s"expected NotAuthenticated, got $other")
      assertEquals(requests, Nil)
  }

  test("mapK identity preserves requests") {
    val identity = new cats.~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair <- media(Response(HttpStatus.Ok, processedJson))
      (ref, media) = pair
      result   <- media.mapK(identity).get(Id("22348641"))
      requests <- ref.get
    yield
      assertEquals(result.id.value, "22348641")
      assertEquals(requests.head.url, "https://code.party/api/v1/media/22348641")
  }
