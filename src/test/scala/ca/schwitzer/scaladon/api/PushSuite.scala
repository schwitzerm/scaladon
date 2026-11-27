package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, PushAlertSettings, PushPolicy}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class PushSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val subscriptionJson: String =
    """{
      |  "id": 328183,
      |  "endpoint": "https://yourdomain.example/listener",
      |  "standard": true,
      |  "alerts": {
      |    "mention": true,
      |    "quote": false,
      |    "poll": true,
      |    "admin.sign_up": false
      |  },
      |  "server_key": "BCk-QqERU0q-CfYZjcuB6lnyyOYfJ2AifKqfeGIm7Z-HiTU5T9eTG5GxVA0_OH5mMlI4UkkDTpaZwozy0TzdZ2M=",
      |  "policy": "followed"
      |}""".stripMargin

  private def recording(response: Response): IO[(Ref[IO, List[Request]], Push[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Push.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  private def form(request: Request): Map[String, String] =
    request.form.getOrElse(fail("expected a form body")).toMap

  test("create posts the subscription keys and decodes the stored alerts") {
    for
      pair      <- recording(Response(HttpStatus.Ok, subscriptionJson))
      (ref, api) = pair
      result    <- api.create("https://yourdomain.example/listener", "p256dh-key", "auth-secret")
      requests  <- ref.get
    yield
      assertEquals(result.id, 328183L)
      assertEquals(result.endpoint, "https://yourdomain.example/listener")
      assertEquals(result.standard, true)
      assertEquals(result.alerts.mention, true)
      assertEquals(result.alerts.quote, false)
      assertEquals(result.alerts.poll, true)
      assertEquals(result.alerts.follow, false)
      assertEquals(result.policy, PushPolicy.Followed)
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/push/subscription")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(
        form(request),
        Map(
          "subscription[endpoint]" -> "https://yourdomain.example/listener",
          "subscription[keys][p256dh]" -> "p256dh-key",
          "subscription[keys][auth]" -> "auth-secret"
        )
      )
  }

  test("create sends only the options supplied, nesting policy and alerts under data") {
    for
      pair      <- recording(Response(HttpStatus.Ok, subscriptionJson))
      (ref, api) = pair
      _ <- api.create(
             "https://yourdomain.example/listener",
             "p256dh-key",
             "auth-secret",
             standard = Some(true),
             alerts = PushAlertSettings(mention = Some(true), adminSignUp = Some(true)),
             policy = Some(PushPolicy.Off)
           )
      requests <- ref.get
    yield
      val fields = form(requests.head)
      assertEquals(fields.get("subscription[standard]"), Some("true"))
      assertEquals(fields.get("data[alerts][mention]"), Some("true"))
      assertEquals(fields.get("data[alerts][admin.sign_up]"), Some("true"))
      assertEquals(fields.get("data[policy]"), Some("none"))
      assert(!fields.contains("data[alerts][follow]"), "absent alert must be omitted")
      assert(
        !fields.contains("data[alerts][follow_request]"),
        "absent alert must be omitted"
      )
  }

  test("get targets the subscription path and defaults an absent policy to all") {
    val withoutPolicy = subscriptionJson.replace(",\n  \"policy\": \"followed\"", "")
    for
      pair      <- recording(Response(HttpStatus.Ok, withoutPolicy))
      (ref, api) = pair
      result    <- api.get
      requests  <- ref.get
    yield
      assertEquals(result.policy, PushPolicy.All)
      assertEquals(result.alerts.favourite, false)
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/push/subscription")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("update PUTs only the supplied alert flags and policy") {
    for
      pair      <- recording(Response(HttpStatus.Ok, subscriptionJson))
      (ref, api) = pair
      result <- api.update(
                  alerts = PushAlertSettings(mention = Some(false), quote = Some(true)),
                  policy = Some(PushPolicy.Follower)
                )
      requests <- ref.get
    yield
      assertEquals(result.alerts.mention, true)
      val request = requests.head
      assertEquals(request.method, HttpMethod.PUT)
      assertEquals(request.url, "https://code.party/api/v1/push/subscription")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(
        form(request),
        Map(
          "data[alerts][mention]" -> "false",
          "data[alerts][quote]" -> "true",
          "data[policy]" -> "follower"
        )
      )
  }

  test("delete removes the subscription") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.delete.attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      val request = requests.head
      assertEquals(request.method, HttpMethod.DELETE)
      assertEquals(request.url, "https://code.party/api/v1/push/subscription")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("a 404 on get is structured and non-retryable") {
    for
      pair    <- recording(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, api) = pair
      result  <- api.get.attempt
    yield
      val error = apiError(result)
      assert(error.isNotFound, s"expected isNotFound, got $error")
      assertEquals(error.retryable, false)
  }

  test("a 422 on create stays an Http error carrying the server message") {
    for
      pair <- recording(
                Response(HttpStatus(422), """{"error":"Validation failed: Endpoint is invalid"}""")
              )
      (_, api) = pair
      result   <- api.create("not-a-url", "p256dh-key", "auth-secret").attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.Http(
          HttpStatus(422),
          Some("Validation failed: Endpoint is invalid"),
          """{"error":"Validation failed: Endpoint is invalid"}"""
        )
      )
  }

  test("a 429 becomes RateLimited with Retry-After on update") {
    for
      pair <- recording(
                Response(HttpStatus(429), """{"error":"Too many requests"}""", Map("Retry-After" -> "30"))
              )
      (_, api) = pair
      result   <- api.update(policy = Some(PushPolicy.All)).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(30.seconds), Some("Too many requests"))
      )
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair    <- recording(Response(HttpStatus.Ok, """{"id":"not-a-number"}"""))
      (_, api) = pair
      result  <- api.get.attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("an anonymous client fails without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, subscriptionJson))
                )
      api    <- IO.pure(Push.live[IO](EndpointRunner.anonymous(backend, host)))
      result <- api.get.attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/push/subscription"))
      assertEquals(sent, 0)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair      <- recording(Response(HttpStatus.Ok, subscriptionJson))
      (_, api)   = pair
      translated = api.mapK(identity)
      result    <- translated.get.attempt
    yield assertEquals(result.map(_.policy), Right(PushPolicy.Followed))
  }
