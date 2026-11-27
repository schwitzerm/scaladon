package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Account, Id}

/** The "Mutes" section: the authenticated list of accounts the user has muted.
  *
  * Muting and unmuting an individual account live on [[Accounts]] (`accounts/:id/mute`); this
  * namespace only exposes the paginated view. The server documents `max_id`, `since_id` and
  * `limit` here — unlike blocks, there is no `min_id`.
  */
trait Mutes[F[_]]:

  /** `GET /api/v1/mutes`, newest first. */
  def list(
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Account]]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Mutes[G]

object Mutes:

  def live[F[_]](runner: EndpointRunner[F]): Mutes[F] =
    new LiveMutes[F](runner)

  /** `GET /api/v1/mutes` — pagination is opt-in; absent parameters are not emitted. */
  def listEndpoint(
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      limit: Option[Int] = None
  ): Endpoint[List[Account]] =
    Endpoint.get[List[Account]](
      "/api/v1/mutes",
      query = Accounts.param("max_id", maxId.map(_.value)) ++
        Accounts.param("since_id", sinceId.map(_.value)) ++
        Accounts.param("limit", limit)
    )

private final class LiveMutes[F[_]](runner: EndpointRunner[F]) extends Mutes[F]:

  def list(
      maxId: Option[Id],
      sinceId: Option[Id],
      limit: Option[Int]
  ): F[List[Account]] =
    runner.run(Mutes.listEndpoint(maxId, sinceId, limit))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Mutes[G] =
    new LiveMutes[G](runner.mapK(transform))
