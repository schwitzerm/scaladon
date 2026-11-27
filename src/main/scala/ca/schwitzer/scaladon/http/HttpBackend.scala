package ca.schwitzer.scaladon.http

import cats.{Applicative, ~>}

/** The single seam between pure code and the network.
  *
  * Everything above this trait is total and effect-polymorphic; an interpreter (Akka, http4s,
  * sttp, a test stub) supplies `F`. `mapK` is a natural transformation between effect categories:
  * it changes `F` to `G` without touching requests, responses or the client that uses them. That is
  * the translation mechanism the refactor relies on — a `Future`-based legacy transport can be
  * lifted into `IO` (see `translation.FutureTranslation`) and consumed by the new API unchanged.
  */
trait HttpBackend[F[_]]:
  self =>
  def run(request: Request): F[Response]

  def mapK[G[_]](transform: F ~> G): HttpBackend[G] = new HttpBackend[G]:
    def run(request: Request): G[Response] = transform(self.run(request))

object HttpBackend:

  def apply[F[_]](using backend: HttpBackend[F]): HttpBackend[F] = backend

  def of[F[_]](f: Request => F[Response]): HttpBackend[F] = (request: Request) => f(request)

  /** Answers every request with the same response. Useful for tests and documentation. */
  def const[F[_]: Applicative](response: Response): HttpBackend[F] =
    of(_ => Applicative[F].pure(response))
