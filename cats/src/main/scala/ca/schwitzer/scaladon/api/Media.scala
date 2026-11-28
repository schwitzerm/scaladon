package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, MediaAttachment}
import ca.schwitzer.scaladon.http.{HttpMethod, MultipartPart}

/** The "Media" section of the Mastodon API, as an algebra of values.
  *
  * Uploads are asynchronous for large media: `POST /api/v2/media` answers `200` with a processed
  * attachment or `202` with an attachment whose `url` is still `None`. Poll [[get]] until the URL
  * appears (it answers `206` while processing). Every call needs `write:media`.
  */
trait Media[F[_]]:

  /** `POST /api/v2/media`.
    *
    * `file`'s bytes, filename and content type are used; the multipart field name is set to `file`
    * by the endpoint. `thumbnail`, `description` and `focus` are optional. `focus` is `x,y` with
    * both coordinates in `[-1.0, 1.0]`.
    */
  def upload(
      file: MultipartPart,
      thumbnail: Option[MultipartPart] = None,
      description: Option[String] = None,
      focus: Option[String] = None
  ): F[MediaAttachment]

  /** `GET /api/v1/media/{id}` — the processing check for an asynchronous upload. */
  def get(id: Id): F[MediaAttachment]

  /** `PUT /api/v1/media/{id}`: alternate text, focal point and custom thumbnail. */
  def update(
      id: Id,
      description: Option[String] = None,
      focus: Option[String] = None,
      thumbnail: Option[MultipartPart] = None
  ): F[MediaAttachment]

  /** `DELETE /api/v1/media/{id}` — only for attachments not yet attached to a status. */
  def delete(id: Id): F[Unit]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Media[G]

object Media:

  def live[F[_]](runner: EndpointRunner[F]): Media[F] =
    new LiveMedia[F](runner)

  def uploadEndpoint(
      file: MultipartPart,
      thumbnail: Option[MultipartPart],
      description: Option[String],
      focus: Option[String]
  ): Endpoint[MediaAttachment] =
    val parts =
      fielded("file", file) ::
        thumbnail.toList.map(fielded("thumbnail", _)) :::
        description.toList.map(text => MultipartPart.text("description", text)) :::
        focus.toList.map(value => MultipartPart.text("focus", value))
    Endpoint.multipart[MediaAttachment](HttpMethod.POST, "/api/v2/media", parts)

  def getEndpoint(id: Id): Endpoint[MediaAttachment] =
    Endpoint.get[MediaAttachment](s"/api/v1/media/${id.value}")

  def updateEndpoint(
      id: Id,
      description: Option[String],
      focus: Option[String],
      thumbnail: Option[MultipartPart]
  ): Endpoint[MediaAttachment] =
    val parts =
      thumbnail.toList.map(fielded("thumbnail", _)) :::
        description.toList.map(text => MultipartPart.text("description", text)) :::
        focus.toList.map(value => MultipartPart.text("focus", value))
    Endpoint.multipart[MediaAttachment](HttpMethod.PUT, s"/api/v1/media/${id.value}", parts)

  def deleteEndpoint(id: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, s"/api/v1/media/${id.value}")

  /** The field name belongs to the endpoint, not to the caller's file value. */
  private def fielded(name: String, part: MultipartPart): MultipartPart =
    part.copy(name = name)

private final class LiveMedia[F[_]](runner: EndpointRunner[F]) extends Media[F]:

  def upload(
      file: MultipartPart,
      thumbnail: Option[MultipartPart],
      description: Option[String],
      focus: Option[String]
  ): F[MediaAttachment] =
    runner.run(Media.uploadEndpoint(file, thumbnail, description, focus))

  def get(id: Id): F[MediaAttachment] =
    runner.run(Media.getEndpoint(id))

  def update(
      id: Id,
      description: Option[String],
      focus: Option[String],
      thumbnail: Option[MultipartPart]
  ): F[MediaAttachment] =
    runner.run(Media.updateEndpoint(id, description, focus, thumbnail))

  def delete(id: Id): F[Unit] =
    runner.run(Media.deleteEndpoint(id))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Media[G] =
    new LiveMedia[G](runner.mapK(transform))
