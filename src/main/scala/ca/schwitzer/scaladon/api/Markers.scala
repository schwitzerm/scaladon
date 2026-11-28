package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, Marker, MarkerTimeline}

/** The "Markers" section of the Mastodon API.
  *
  * A marker is the last read position in a timeline, saved so the client can resume where the user
  * left off. Both calls answer with a hash keyed by timeline (`home`, `notifications`), modelled as
  * a typed map; an unknown timeline key fails decoding.
  *
  * `F` is the effect; expected failures are raised as `ApiException(ApiError)`. A stale write
  * answers `409`, surfacing as `ApiError.Http(HttpStatus(409), …)`.
  */
trait Markers[F[_]]:

  /** `GET /api/v1/markers`; with no timeline requested the server returns an empty object. */
  def get(timelines: List[MarkerTimeline] = Nil): F[Map[MarkerTimeline, Marker]]

  /** `POST /api/v1/markers` (form); each supplied position is sent as
    * `<timeline>[last_read_id]`, and an absent one is omitted.
    */
  def save(
      home: Option[Id] = None,
      notifications: Option[Id] = None
  ): F[Map[MarkerTimeline, Marker]]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Markers[G]

object Markers:

  def live[F[_]](runner: EndpointRunner[F]): Markers[F] =
    new LiveMarkers[F](runner)

  def getEndpoint(timelines: List[MarkerTimeline]): Endpoint[Map[MarkerTimeline, Marker]] =
    Endpoint.get[Map[MarkerTimeline, Marker]](
      "/api/v1/markers",
      query = timelines.map(timeline => "timeline[]" -> timeline.wire)
    )

  def saveEndpoint(
      home: Option[Id],
      notifications: Option[Id]
  ): Endpoint[Map[MarkerTimeline, Marker]] =
    Endpoint.postForm[Map[MarkerTimeline, Marker]](
      "/api/v1/markers",
      home.toList.map(id => "home[last_read_id]" -> id.value) ++
        notifications.toList.map(id => "notifications[last_read_id]" -> id.value)
    )

private final class LiveMarkers[F[_]](runner: EndpointRunner[F]) extends Markers[F]:

  def get(timelines: List[MarkerTimeline]): F[Map[MarkerTimeline, Marker]] =
    runner.run(Markers.getEndpoint(timelines))

  def save(
      home: Option[Id],
      notifications: Option[Id]
  ): F[Map[MarkerTimeline, Marker]] =
    runner.run(Markers.saveEndpoint(home, notifications))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Markers[G] =
    new LiveMarkers[G](runner.mapK(transform))
