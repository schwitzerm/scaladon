package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import ca.schwitzer.scaladon.Mastodon
import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, Request, Response}

class MastodonSuite extends CatsEffectSuite:

  private val host  = Host("code.party")
  private val token = BearerToken("secret")

  private def recording(body: String): IO[(Ref[IO, List[Request]], Mastodon[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(Response(HttpStatus.Ok, body)))
      ref -> Mastodon.live(backend, host, token)
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("anonymous exposes public algebras and blocks authenticated ones before the network") {
    for
      ref <- Ref.of[IO, List[Request]](Nil)
      backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(Response(HttpStatus.Ok, "[]")))
      client  = Mastodon.anonymous(backend, host)
      timeline <- client.timelines.publicTimeline()
      blocked  <- client.profile.get.attempt
      sent     <- ref.get
    yield
      assertEquals(timeline, Nil)
      assertEquals(apiError(blocked), ApiError.NotAuthenticated("/api/v1/profile"))
      assertEquals(sent.size, 1)
      assert(!sent.head.headers.contains("Authorization"))
  }

  test("live sends the token on an authenticated algebra") {
    for
      pair     <- recording("{}")
      (ref, client) = pair
      _        <- client.notifications.clear()
      requests <- ref.get
    yield
      assertEquals(requests.head.url, "https://code.party/api/v1/notifications/clear")
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("mapK composes: two identity translations behave like one") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa

    for
      pair  <- recording("[]")
      (ref, client) = pair
      once  <- client.mapK(identity).timelines.publicTimeline()
      twice <- client.mapK(identity).mapK(identity).timelines.publicTimeline()
      requests <- ref.get
    yield
      assertEquals(once, twice)
      assertEquals(requests.map(_.url).distinct, List("https://code.party/api/v1/timelines/public"))
  }

  test("mapK preserves the anonymous client's authentication behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa

    for
      ref <- Ref.of[IO, List[Request]](Nil)
      backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(Response(HttpStatus.Ok, "{}")))
      translated = Mastodon.anonymous(backend, host).mapK(identity)
      result <- translated.accounts.verifyCredentials().attempt
      sent   <- ref.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/accounts/verify_credentials"))
      assertEquals(sent, Nil)
  }
