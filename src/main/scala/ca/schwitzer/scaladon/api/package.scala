package ca.schwitzer.scaladon

import cats.MonadThrow

import ca.schwitzer.scaladon.domain.{ApiError, ApiException}

package object api:

  extension [F[_], A](fa: F[A])
    /** Turn a typed API failure into a value, leaving defects and cancellation in the effect.
      *
      * `fa.attempt` would also capture `OutOfMemoryError`, bugs and cancellation; this catches only
      * [[ApiException]] so the error channel keeps meaning what it should.
      */
    def attemptApi(using F: MonadThrow[F]): F[Either[ApiError, A]] =
      F.handleErrorWith(F.map(fa)(Right(_))) {
        case ApiException(error) => F.pure(Left(error))
        case other               => F.raiseError(other)
      }
