package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, Suggestion}
import ca.schwitzer.scaladon.http.HttpMethod

/** The "Suggestions" section: server-generated follow recommendations (v2, API version 9+).
  *
  * The v2 shape is a list of [[ca.schwitzer.scaladon.domain.Suggestion]] values, each with the
  * account and every reason it was suggested; the deprecated v1 shape (bare accounts) is not
  * modelled, since the adapter targets the latest API only.
  */
trait Suggestions[F[_]]:

  /** `GET /api/v2/suggestions`; `limit` defaults to 40 on the server and is capped at 80. */
  def list(limit: Option[Int] = None): F[List[Suggestion]]

  /** `DELETE /api/v1/suggestions/{accountId}`; the server answers `{}` even for an unknown id. */
  def remove(accountId: Id): F[Unit]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Suggestions[G]

object Suggestions:

  def live[F[_]](runner: EndpointRunner[F]): Suggestions[F] =
    new LiveSuggestions[F](runner)

  /** `GET /api/v2/suggestions`; the limit is omitted when absent rather than sent empty. */
  def listEndpoint(limit: Option[Int]): Endpoint[List[Suggestion]] =
    Endpoint.get[List[Suggestion]](
      "/api/v2/suggestions",
      limit.toList.map(value => "limit" -> value.toString)
    )

  /** `DELETE /api/v1/suggestions/{accountId}`. */
  def removeEndpoint(accountId: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, s"/api/v1/suggestions/${accountId.value}")

private final class LiveSuggestions[F[_]](runner: EndpointRunner[F]) extends Suggestions[F]:

  def list(limit: Option[Int]): F[List[Suggestion]] =
    runner.run(Suggestions.listEndpoint(limit))

  def remove(accountId: Id): F[Unit] =
    runner.run(Suggestions.removeEndpoint(accountId))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Suggestions[G] =
    new LiveSuggestions[G](runner.mapK(transform))
