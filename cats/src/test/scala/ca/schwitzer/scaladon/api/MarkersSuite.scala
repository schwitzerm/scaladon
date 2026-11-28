package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id, Marker, MarkerTimeline}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class MarkersSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val markersJson: String =
    """{
      |  "notifications": {
      |    "last_read_id": "35098814",
      |    "version": 361,
      |    "updated_at": "2019-11-26T22:37:25.239Z"
      |  },
      |  "home": {
      |    "last_read_id": "103206604258487607",
      |    "version": 468,
      |    "updated_at": "2019-11-26T22:37:25.235Z"
      |  }
      |}""".stripMargin

  private val homeJson: String =
    """{
      |  "home": {
      |    "last_read_id": "103194548672408537",
      |    "version": 462,
      |    "updated_at": "2019-11-24T19:39:39.337Z"
      |  }
      |}""".stripMargin

  private def recording(
      response: Response
  ): IO[(Ref[IO, List[Request]], Markers[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Markers.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("get sends repeated timeline[] parameters and decodes the hash by timeline") {
    for
      pair      <- recording(Response(HttpStatus.Ok, markersJson))
      (ref, api) = pair
      result    <- api.get(List(MarkerTimeline.Home, MarkerTimeline.Notifications))
      requests  <- ref.get
    yield
      assertEquals(result.keySet, Set(MarkerTimeline.Home, MarkerTimeline.Notifications))
      assertEquals(result(MarkerTimeline.Home).lastReadId.value, "103206604258487607")
      assertEquals(result(MarkerTimeline.Home).version, 468)
      assertEquals(result(MarkerTimeline.Notifications).version, 361)
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(
        request.url,
        "https://code.party/api/v1/markers?timeline%5B%5D=home&timeline%5B%5D=notifications"
      )
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("get without timelines sends no query string") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.get()
      requests  <- ref.get
    yield
      assertEquals(result, Map.empty[MarkerTimeline, Marker])
      assertEquals(requests.head.url, "https://code.party/api/v1/markers")
  }

  test("save POSTs both positions as form fields") {
    for
      pair      <- recording(Response(HttpStatus.Ok, markersJson))
      (ref, api) = pair
      result <- api.save(
                  home = Some(Id("103194548672408537")),
                  notifications = Some(Id("35098814"))
                )
      requests <- ref.get
    yield
      assertEquals(result(MarkerTimeline.Notifications).lastReadId.value, "35098814")
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/markers")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(
        request.form,
        Some(
          List(
            "home[last_read_id]" -> "103194548672408537",
            "notifications[last_read_id]" -> "35098814"
          )
        )
      )
  }

  test("save omits the position it was not given") {
    for
      pair      <- recording(Response(HttpStatus.Ok, homeJson))
      (ref, api) = pair
      result    <- api.save(home = Some(Id("103194548672408537")))
      requests  <- ref.get
    yield
      assertEquals(result.keySet, Set(MarkerTimeline.Home))
      assertEquals(result(MarkerTimeline.Home).version, 462)
      assertEquals(
        requests.head.form,
        Some(List("home[last_read_id]" -> "103194548672408537"))
      )
  }

  test("a 409 conflict surfaces as an Http error") {
    for
      pair <- recording(
                Response(HttpStatus(409), """{"error":"Conflict during update, please try again"}""")
              )
      (_, api) = pair
      result   <- api.save(home = Some(Id("1"))).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.Http(
          HttpStatus(409),
          Some("Conflict during update, please try again"),
          """{"error":"Conflict during update, please try again"}"""
        )
      )
  }

  test("a 429 becomes RateLimited with Retry-After") {
    for
      pair <- recording(
                Response(
                  HttpStatus(429),
                  """{"error":"Too many requests"}""",
                  Map("Retry-After" -> "30")
                )
              )
      (_, api) = pair
      result   <- api.get(List(MarkerTimeline.Home)).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(30.seconds), Some("Too many requests"))
      )
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair    <- recording(Response(HttpStatus.Ok, """{"home":{"last_read_id":"1"}}"""))
      (_, api) = pair
      result  <- api.get(List(MarkerTimeline.Home)).attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("an anonymous client fails both calls without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, markersJson))
                )
      api    = Markers.live[IO](EndpointRunner.anonymous(backend, host))
      result <- api.get(List(MarkerTimeline.Home)).attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/markers"))
      assertEquals(sent, 0)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair      <- recording(Response(HttpStatus.Ok, homeJson))
      (_, api)   = pair
      translated = api.mapK(identity)
      result    <- translated.save(home = Some(Id("103194548672408537"))).attempt
    yield assertEquals(result.map(_.keySet), Right(Set(MarkerTimeline.Home)))
  }
