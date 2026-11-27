package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}
import io.circe.Json

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, Status, Visibility}
import ca.schwitzer.scaladon.http.HttpMethod

/** The "Statuses" section of the Mastodon API, as an algebra of values.
  *
  * `F` is the effect. Expected failures are raised inside `F` as `ApiException(ApiError)`, so
  * ordinary effect combinators apply; use `attemptApi` when you want the error as a value.
  */
trait Statuses[F[_]]:
  def fetch(id: Id): F[Status]

  def post(
      text: String,
      visibility: Visibility = Visibility.Public,
      inReplyToId: Option[Id] = None,
      spoilerText: Option[String] = None
  ): F[Status]

  def delete(id: Id): F[Unit]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Statuses[G]

object Statuses:

  def live[F[_]](runner: EndpointRunner[F]): Statuses[F] =
    new LiveStatuses[F](runner)

  /** `GET /api/v1/statuses/{id}` — public, no token. */
  def fetchEndpoint(id: Id): Endpoint[Status] =
    Endpoint.get[Status](s"/api/v1/statuses/${id.value}", authenticated = false)

  /** `POST /api/v1/statuses` — absent options are omitted, never sent as JSON `null`. */
  def postEndpoint(
      text: String,
      visibility: Visibility,
      inReplyToId: Option[Id],
      spoilerText: Option[String]
  ): Endpoint[Status] =
    Endpoint.post[Status]("/api/v1/statuses", postBody(text, visibility, inReplyToId, spoilerText))

  /** `DELETE /api/v1/statuses/{id}` — answers with `{}`. */
  def deleteEndpoint(id: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, s"/api/v1/statuses/${id.value}")

  private[api] def postBody(
      text: String,
      visibility: Visibility,
      inReplyToId: Option[Id],
      spoilerText: Option[String]
  ): Json =
    val required = List(
      "status"     -> Json.fromString(text),
      "visibility" -> Json.fromString(visibility.wire)
    )
    val optional =
      inReplyToId.toList.map(id => "in_reply_to_id" -> Json.fromString(id.value)) ++
        spoilerText.filter(_.nonEmpty).toList.map(text => "spoiler_text" -> Json.fromString(text))
    Json.fromFields(required ++ optional)

private final class LiveStatuses[F[_]](runner: EndpointRunner[F]) extends Statuses[F]:

  def fetch(id: Id): F[Status] =
    runner.run(Statuses.fetchEndpoint(id))

  def post(
      text: String,
      visibility: Visibility,
      inReplyToId: Option[Id],
      spoilerText: Option[String]
  ): F[Status] =
    runner.run(Statuses.postEndpoint(text, visibility, inReplyToId, spoilerText))

  def delete(id: Id): F[Unit] =
    runner.run(Statuses.deleteEndpoint(id))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Statuses[G] =
    new LiveStatuses[G](runner.mapK(transform))
