package ca.schwitzer.scaladon.translation

import cats.~>
import cats.effect.IO
import cats.effect.std.Dispatcher

import scala.concurrent.Future

import ca.schwitzer.scaladon.http.HttpBackend

/** The translation seam between a `Future`-based transport and effect-polymorphic code.
  *
  * An existing transport is `HttpRequest => Future[HttpResponse]`. Instead of rewriting it, wrap it
  * as `HttpBackend[Future]` and apply `futureBackendToIO`: the natural transformation lifts every
  * response into `IO`, and the pure API layers above never learn that a `Future` was involved.
  * The reverse direction (`ioBackendToFuture`) keeps the old `Future`-based API usable from a new
  * `IO`-based transport.
  *
  * `Future` is not cancelable and starts eagerly; `IO` is lazy and cancelable. Lifting is safe
  * (you gain a lazy wrapper around an already eager computation); demoting loses cancellation.
  */
object FutureTranslation:

  def ioFromFuture[A](future: => Future[A]): IO[A] =
    IO.fromFuture(IO(future))

  def futureFromIO[A](io: IO[A])(using dispatcher: Dispatcher[IO]): Future[A] =
    dispatcher.unsafeToFuture(io)

  val futureToIO: Future ~> IO = new (Future ~> IO):
    def apply[A](fa: Future[A]): IO[A] = IO.fromFuture(IO.pure(fa))

  def ioToFuture(using dispatcher: Dispatcher[IO]): IO ~> Future = new (IO ~> Future):
    def apply[A](fa: IO[A]): Future[A] = dispatcher.unsafeToFuture(fa)

  def futureBackendToIO(backend: HttpBackend[Future]): HttpBackend[IO] =
    backend.mapK(futureToIO)

  def ioBackendToFuture(backend: HttpBackend[IO])(using dispatcher: Dispatcher[IO]): HttpBackend[Future] =
    backend.mapK(ioToFuture)
