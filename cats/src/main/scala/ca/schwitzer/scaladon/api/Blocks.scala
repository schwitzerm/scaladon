package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Account, Id}

/** The "Blocks" section: the authenticated list of accounts the user has blocked.
  *
  * Blocking and unblocking an individual account live on [[Accounts]] (`accounts/:id/block`);
  * this namespace only exposes the paginated view, which is why the algebra is a single method.
  */
trait Blocks[F[_]]:

  /** `GET /api/v1/blocks`, newest first; both `min_id` and `max_id` may be given at once. */
  def list(
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Account]]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Blocks[G]

object Blocks:

  def live[F[_]](runner: EndpointRunner[F]): Blocks[F] =
    new LiveBlocks[F](runner)

  /** `GET /api/v1/blocks` — pagination is opt-in; absent parameters are not emitted. */
  def listEndpoint(
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): Endpoint[List[Account]] =
    Endpoint.get[List[Account]](
      "/api/v1/blocks",
      query = Accounts.param("max_id", maxId.map(_.value)) ++
        Accounts.param("since_id", sinceId.map(_.value)) ++
        Accounts.param("min_id", minId.map(_.value)) ++
        Accounts.param("limit", limit)
    )

private final class LiveBlocks[F[_]](runner: EndpointRunner[F]) extends Blocks[F]:

  def list(
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[Account]] =
    runner.run(Blocks.listEndpoint(maxId, sinceId, minId, limit))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Blocks[G] =
    new LiveBlocks[G](runner.mapK(transform))
