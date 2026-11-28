package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}
import io.circe.{Decoder, Json}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{ApiError, AsyncRefreshStatus}
import ca.schwitzer.scaladon.http.HttpMethod

/** The experimental "Async Refreshes" endpoint (`GET /api/v1_alpha/async_refreshes/:id`).
  *
  * Some responses announce a background job by sending a `Mastodon-Async-Refresh` header (see
  * [[Statuses.context]]); this algebra polls that job by the header's id. The server answers with
  * the entity wrapped under `async_refresh`, which [[AsyncRefreshes.getEndpoint]] unwraps while
  * decoding, so callers never see the envelope.
  *
  * The `v1_alpha` prefix is deliberately experimental: Mastodon may still change the shape, and
  * will keep the endpoint for one version after promoting it. `F` is the effect; expected failures
  * are raised as `ApiException(ApiError)`, and the refresh id is the opaque string from the header.
  */
trait AsyncRefreshes[F[_]]:

  /** `GET /api/v1_alpha/async_refreshes/{id}` — needs a user token with `read` scope. */
  def get(id: String): F[AsyncRefreshStatus]

  def mapK[G[_]: MonadThrow](transform: F ~> G): AsyncRefreshes[G]

object AsyncRefreshes:

  def live[F[_]](runner: EndpointRunner[F]): AsyncRefreshes[F] =
    new LiveAsyncRefreshes[F](runner)

  /** `GET /api/v1_alpha/async_refreshes/{id}`; the entity is wrapped under `async_refresh`. */
  def getEndpoint(id: String): Endpoint[AsyncRefreshStatus] =
    Endpoint(
      HttpMethod.GET,
      s"/api/v1_alpha/async_refreshes/$id",
      Nil,
      None,
      authenticated = true,
      decode = field[AsyncRefreshStatus]("async_refresh")
    )

  /** Decode a value the API wraps under a named key, keeping the plain entity decoder reusable. */
  private def field[A](name: String)(using Decoder[A]): Json => Either[ApiError, A] =
    json =>
      json.hcursor.downField(name).as[A].left.map(failure => ApiError.Decode(failure.message))

private final class LiveAsyncRefreshes[F[_]](runner: EndpointRunner[F]) extends AsyncRefreshes[F]:

  def get(id: String): F[AsyncRefreshStatus] =
    runner.run(AsyncRefreshes.getEndpoint(id))

  def mapK[G[_]: MonadThrow](transform: F ~> G): AsyncRefreshes[G] =
    new LiveAsyncRefreshes[G](runner.mapK(transform))
