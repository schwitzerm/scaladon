package ca.schwitzer.scaladon.translation

import cats.effect.IO
import cats.effect.std.Dispatcher
import munit.CatsEffectSuite

import scala.concurrent.Future

import ca.schwitzer.scaladon.domain.HttpStatus
import ca.schwitzer.scaladon.http.{HttpBackend, HttpMethod, Request, Response}

class FutureTranslationSuite extends CatsEffectSuite:

  test("ioFromFuture lifts a successful Future") {
    FutureTranslation.ioFromFuture(Future.successful(41)).map(value => assertEquals(value, 41))
  }

  test("ioFromFuture surfaces a failed Future as an effect failure") {
    FutureTranslation
      .ioFromFuture(Future.failed[Int](new RuntimeException("boom")))
      .attempt
      .map(result => assert(result.isLeft, s"expected failure, got $result"))
  }

  test("futureFromIO completes a Future from an IO") {
    Dispatcher.parallel[IO].use { dispatcher =>
      given Dispatcher[IO] = dispatcher
      IO.fromFuture(IO(FutureTranslation.futureFromIO(IO.pure(7))))
        .map(value => assertEquals(value, 7))
    }
  }

  test("a Future-based backend can be lifted into IO") {
    val futureBackend: HttpBackend[Future] =
      HttpBackend.of[Future](_ => Future.successful(Response(HttpStatus.Ok, "{}")))
    FutureTranslation
      .futureBackendToIO(futureBackend)
      .run(Request(HttpMethod.GET, "https://code.party/api/v1/instance"))
      .map(response => assertEquals(response.status, HttpStatus.Ok))
  }

  test("an IO-based backend can be demoted to Future") {
    Dispatcher.parallel[IO].use { dispatcher =>
      given Dispatcher[IO] = dispatcher
      val modern: HttpBackend[IO] =
        HttpBackend.of[IO](_ => IO.pure(Response(HttpStatus.Created, "{}")))
      IO.fromFuture(IO(FutureTranslation.ioBackendToFuture(modern).run(Request(HttpMethod.POST, "https://x/y"))))
        .map(response => assertEquals(response.status, HttpStatus.Created))
    }
  }
