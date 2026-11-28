package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Announcement, Id}
import ca.schwitzer.scaladon.http.HttpMethod

/** The "Announcements" section of the Mastodon API.
  *
  * Announcements are server-wide messages set by the administration. `list` returns the currently
  * active ones; `read` on each is viewer-relative, so every call needs a user token and an
  * anonymous client fails with `ApiError.NotAuthenticated` before any request is sent.
  *
  * Reactions (Mastodon 5.0, API version 12) can be disabled per announcement. The server then
  * answers `POST`/`DELETE /api/v1/announcements/:id/reactions/:name` with `400`, surfacing as
  * `ApiError.Http(HttpStatus(400), …)`; `Announcement.reactionsAllowed` says in advance whether to
  * offer the affordance. An unrecognised emoji name answers `422`, also an `ApiError.Http`. Neither
  * is retryable. `name` is either a Unicode emoji or a custom emoji shortcode; it occupies one path
  * segment and is percent-encoded as such.
  */
trait Announcements[F[_]]:

  /** `GET /api/v1/announcements` — requires a user token. */
  def list(): F[List[Announcement]]

  /** `POST /api/v1/announcements/{id}/dismiss` — requires `write:accounts`; answers `{}`. A missing
    * announcement answers `404`, `ApiError.Http` with `isNotFound`.
    */
  def dismiss(id: Id): F[Unit]

  /** `PUT /api/v1/announcements/{id}/reactions/{name}` — requires `write:favourites`; answers `{}`. */
  def addReaction(id: Id, name: String): F[Unit]

  /** `DELETE /api/v1/announcements/{id}/reactions/{name}` — requires `write:favourites`; answers `{}`. */
  def removeReaction(id: Id, name: String): F[Unit]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Announcements[G]

object Announcements:

  def live[F[_]](runner: EndpointRunner[F]): Announcements[F] =
    new LiveAnnouncements[F](runner)

  /** `GET /api/v1/announcements` — authenticated. */
  def listEndpoint: Endpoint[List[Announcement]] =
    Endpoint.get[List[Announcement]]("/api/v1/announcements")

  /** `POST /api/v1/announcements/{id}/dismiss` — answers with an empty object. */
  def dismissEndpoint(id: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.POST, s"/api/v1/announcements/${id.value}/dismiss")

  /** `PUT /api/v1/announcements/{id}/reactions/{name}` — answers with an empty object. */
  def addReactionEndpoint(id: Id, name: String): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.PUT, reactionPath(id, name))

  /** `DELETE /api/v1/announcements/{id}/reactions/{name}` — answers with an empty object. */
  def removeReactionEndpoint(id: Id, name: String): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, reactionPath(id, name))

  private def reactionPath(id: Id, name: String): String =
    s"/api/v1/announcements/${id.value}/reactions/${encodePathSegment(name)}"

  /** One path segment: `+` from the form encoder is not valid in a path, so it becomes `%20`. */
  private def encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private final class LiveAnnouncements[F[_]](runner: EndpointRunner[F]) extends Announcements[F]:

  def list(): F[List[Announcement]] =
    runner.run(Announcements.listEndpoint)

  def dismiss(id: Id): F[Unit] =
    runner.run(Announcements.dismissEndpoint(id))

  def addReaction(id: Id, name: String): F[Unit] =
    runner.run(Announcements.addReactionEndpoint(id, name))

  def removeReaction(id: Id, name: String): F[Unit] =
    runner.run(Announcements.removeReactionEndpoint(id, name))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Announcements[G] =
    new LiveAnnouncements[G](runner.mapK(transform))
