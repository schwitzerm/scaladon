package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Account, Id, Relationship}

/** The "Follow requests" section: pending requests on a locked account, and their decisions.
  *
  * `authorize` and `reject` carry no body, so they are empty `POST`s; the server answers with the
  * updated [[Relationship]], not with `{}`. A request for an account with no pending request is a
  * `404` and surfaces as [[ca.schwitzer.scaladon.domain.ApiError.Http]].
  */
trait FollowRequests[F[_]]:

  /** `GET /api/v1/follow_requests`, newest first. */
  def list(
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Account]]

  /** `POST /api/v1/follow_requests/:account_id/authorize`. */
  def authorize(id: Id): F[Relationship]

  /** `POST /api/v1/follow_requests/:account_id/reject`. */
  def reject(id: Id): F[Relationship]

  def mapK[G[_]: MonadThrow](transform: F ~> G): FollowRequests[G]

object FollowRequests:

  def live[F[_]](runner: EndpointRunner[F]): FollowRequests[F] =
    new LiveFollowRequests[F](runner)

  /** `GET /api/v1/follow_requests` — pagination is opt-in; absent parameters are not emitted. */
  def listEndpoint(
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): Endpoint[List[Account]] =
    Endpoint.get[List[Account]](
      "/api/v1/follow_requests",
      query = Accounts.param("max_id", maxId.map(_.value)) ++
        Accounts.param("since_id", sinceId.map(_.value)) ++
        Accounts.param("min_id", minId.map(_.value)) ++
        Accounts.param("limit", limit)
    )

  /** `POST /api/v1/follow_requests/:account_id/authorize` — empty form body. */
  def authorizeEndpoint(id: Id): Endpoint[Relationship] =
    Endpoint.postForm[Relationship](s"/api/v1/follow_requests/${id.value}/authorize", Nil)

  /** `POST /api/v1/follow_requests/:account_id/reject` — empty form body. */
  def rejectEndpoint(id: Id): Endpoint[Relationship] =
    Endpoint.postForm[Relationship](s"/api/v1/follow_requests/${id.value}/reject", Nil)

private final class LiveFollowRequests[F[_]](runner: EndpointRunner[F]) extends FollowRequests[F]:

  def list(
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[Account]] =
    runner.run(FollowRequests.listEndpoint(maxId, sinceId, minId, limit))

  def authorize(id: Id): F[Relationship] =
    runner.run(FollowRequests.authorizeEndpoint(id))

  def reject(id: Id): F[Relationship] =
    runner.run(FollowRequests.rejectEndpoint(id))

  def mapK[G[_]: MonadThrow](transform: F ~> G): FollowRequests[G] =
    new LiveFollowRequests[G](runner.mapK(transform))
