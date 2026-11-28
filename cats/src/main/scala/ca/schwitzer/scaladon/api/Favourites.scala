package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, Status}

/** The "Favourites" section of the Mastodon API.
  *
  * `list` returns `F[List[Status]]`; expected failures are raised inside `F` as
  * `ApiException(ApiError)`. Favourites are private, so the endpoint always carries the user token
  * (`read:favourites`); an anonymous client raises `ApiError.NotAuthenticated` before sending.
  *
  * Favourite ids are not exposed, so cursors are the ids of the favourited statuses the caller
  * already has: `max_id`/`since_id` bound and `min_id` pages forward, as in the timelines.
  * `max_id` and `min_id` may be combined (Mastodon 3.3+). `limit` is capped by the server at 40.
  * The favourite/unfavourite actions themselves live on [[Statuses]].
  */
trait Favourites[F[_]]:

  def list(
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Status]]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Favourites[G]

object Favourites:

  def live[F[_]](runner: EndpointRunner[F]): Favourites[F] =
    new LiveFavourites[F](runner)

  /** `GET /api/v1/favourites` — authenticated. */
  def listEndpoint(
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): Endpoint[List[Status]] =
    Endpoint.get[List[Status]](
      "/api/v1/favourites",
      query =
        maxId.toList.map(id => "max_id" -> id.value) ++
          sinceId.toList.map(id => "since_id" -> id.value) ++
          minId.toList.map(id => "min_id" -> id.value) ++
          limit.toList.map(value => "limit" -> value.toString)
    )

private final class LiveFavourites[F[_]](runner: EndpointRunner[F]) extends Favourites[F]:

  def list(
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[Status]] =
    runner.run(Favourites.listEndpoint(maxId, sinceId, minId, limit))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Favourites[G] =
    new LiveFavourites[G](runner.mapK(transform))
