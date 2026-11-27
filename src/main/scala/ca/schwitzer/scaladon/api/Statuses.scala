package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}
import io.circe.Json

import java.time.Instant

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{
  Account,
  AsyncRefresh,
  Context,
  Id,
  PreviewCard,
  QuoteApprovalPolicy,
  Status,
  StatusPostResult,
  Visibility
}
import ca.schwitzer.scaladon.http.HttpMethod

/** The "Statuses" section of the Mastodon API, as an algebra of values.
  *
  * `F` is the effect. Expected failures are raised inside `F` as `ApiException(ApiError)`, so
  * ordinary effect combinators apply; use `attemptApi` when you want the error as a value.
  *
  * `post` returns [[StatusPostResult]] because supplying `scheduledAt` makes the server answer with
  * a `ScheduledStatus` instead of a `Status`; the sum type keeps that branch explicit. `context`
  * returns the experimental `Mastodon-Async-Refresh` header alongside the body.
  */
trait Statuses[F[_]]:
  def fetch(id: Id): F[Status]

  def post(
      text: String,
      visibility: Visibility = Visibility.Public,
      inReplyToId: Option[Id] = None,
      spoilerText: Option[String] = None,
      sensitive: Option[Boolean] = None,
      language: Option[String] = None,
      mediaIds: Option[List[Id]] = None,
      quotedStatusId: Option[Id] = None,
      quoteApprovalPolicy: Option[QuoteApprovalPolicy] = None,
      scheduledAt: Option[Instant] = None
  ): F[StatusPostResult]

  def edit(
      id: Id,
      text: Option[String] = None,
      spoilerText: Option[String] = None,
      sensitive: Option[Boolean] = None,
      language: Option[String] = None,
      mediaIds: Option[List[Id]] = None,
      quoteApprovalPolicy: Option[QuoteApprovalPolicy] = None
  ): F[Status]

  def delete(id: Id): F[Unit]

  def context(id: Id): F[(Context, Option[AsyncRefresh])]

  def card(id: Id): F[Option[PreviewCard]]

  def favouritedBy(id: Id, limit: Option[Int] = None): F[List[Account]]

  def rebloggedBy(id: Id, limit: Option[Int] = None): F[List[Account]]

  def favourite(id: Id): F[Status]

  def unfavourite(id: Id): F[Status]

  def reblog(id: Id, visibility: Option[Visibility] = None): F[Status]

  def unreblog(id: Id): F[Status]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Statuses[G]

object Statuses:

  def live[F[_]: MonadThrow](runner: EndpointRunner[F]): Statuses[F] =
    new LiveStatuses[F](runner)

  /** `GET /api/v1/statuses/{id}` — public, no token. */
  def fetchEndpoint(id: Id): Endpoint[Status] =
    Endpoint.get[Status](s"/api/v1/statuses/${id.value}", authenticated = false)

  /** `POST /api/v1/statuses` — absent options are omitted, never sent as JSON `null`. */
  def postEndpoint(
      text: String,
      visibility: Visibility,
      inReplyToId: Option[Id],
      spoilerText: Option[String],
      sensitive: Option[Boolean] = None,
      language: Option[String] = None,
      mediaIds: Option[List[Id]] = None,
      quotedStatusId: Option[Id] = None,
      quoteApprovalPolicy: Option[QuoteApprovalPolicy] = None,
      scheduledAt: Option[Instant] = None
  ): Endpoint[StatusPostResult] =
    Endpoint.post[StatusPostResult](
      "/api/v1/statuses",
      postBody(
        text,
        visibility,
        inReplyToId,
        spoilerText,
        sensitive,
        language,
        mediaIds,
        quotedStatusId,
        quoteApprovalPolicy,
        scheduledAt
      )
    )

  /** `PUT /api/v1/statuses/{id}` — the edit endpoint uses PUT, not PATCH. */
  def editEndpoint(
      id: Id,
      text: Option[String],
      spoilerText: Option[String],
      sensitive: Option[Boolean],
      language: Option[String],
      mediaIds: Option[List[Id]],
      quoteApprovalPolicy: Option[QuoteApprovalPolicy]
  ): Endpoint[Status] =
    Endpoint
      .post[Status](
        s"/api/v1/statuses/${id.value}",
        editBody(text, spoilerText, sensitive, language, mediaIds, quoteApprovalPolicy)
      )
      .copy(method = HttpMethod.PUT)

  /** `DELETE /api/v1/statuses/{id}` — answers with `{}`. */
  def deleteEndpoint(id: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, s"/api/v1/statuses/${id.value}")

  /** `GET /api/v1/statuses/{id}/context` — public for public statuses. */
  def contextEndpoint(id: Id): Endpoint[Context] =
    Endpoint.get[Context](s"/api/v1/statuses/${id.value}/context", authenticated = false)

  /** `GET /api/v1/statuses/{id}/favourited_by` — public for public statuses. */
  def favouritedByEndpoint(id: Id, limit: Option[Int] = None): Endpoint[List[Account]] =
    Endpoint.get[List[Account]](
      s"/api/v1/statuses/${id.value}/favourited_by",
      query = limitQuery(limit),
      authenticated = false
    )

  /** `GET /api/v1/statuses/{id}/reblogged_by` — public for public statuses. */
  def rebloggedByEndpoint(id: Id, limit: Option[Int] = None): Endpoint[List[Account]] =
    Endpoint.get[List[Account]](
      s"/api/v1/statuses/${id.value}/reblogged_by",
      query = limitQuery(limit),
      authenticated = false
    )

  /** `POST /api/v1/statuses/{id}/favourite` — no request body. */
  def favouriteEndpoint(id: Id): Endpoint[Status] =
    Endpoint.postForm[Status](s"/api/v1/statuses/${id.value}/favourite", Nil)

  /** `POST /api/v1/statuses/{id}/unfavourite` — no request body. */
  def unfavouriteEndpoint(id: Id): Endpoint[Status] =
    Endpoint.postForm[Status](s"/api/v1/statuses/${id.value}/unfavourite", Nil)

  /** `POST /api/v1/statuses/{id}/reblog` — optional `visibility` form field. */
  def reblogEndpoint(id: Id, visibility: Option[Visibility]): Endpoint[Status] =
    Endpoint.postForm[Status](
      s"/api/v1/statuses/${id.value}/reblog",
      visibility.toList.map(value => "visibility" -> value.wire)
    )

  /** `POST /api/v1/statuses/{id}/unreblog` — no request body. */
  def unreblogEndpoint(id: Id): Endpoint[Status] =
    Endpoint.postForm[Status](s"/api/v1/statuses/${id.value}/unreblog", Nil)

  /** Decode the experimental `Mastodon-Async-Refresh` header.
    *
    * Format: `id="<string>", retry=<int>, result_count=<int>`; `result_count` is optional and the
    * whole header is optional. An unparsable header is treated as absent rather than failing a
    * otherwise-successful request.
    */
  def parseAsyncRefresh(header: String): Option[AsyncRefresh] =
    val fields = header
      .split(",")
      .toList
      .flatMap { part =>
        part.split("=", 2) match
          case Array(key, value) =>
            Some(key.trim -> value.trim.stripPrefix("\"").stripSuffix("\""))
          case _ => None
      }
      .toMap
    for
      id    <- fields.get("id").filter(_.nonEmpty)
      retry <- fields.get("retry").flatMap(_.toIntOption)
    yield AsyncRefresh(id, retry, fields.get("result_count").flatMap(_.toIntOption))

  private def limitQuery(limit: Option[Int]): List[(String, String)] =
    limit.toList.map(value => "limit" -> value.toString)

  private[api] def postBody(
      text: String,
      visibility: Visibility,
      inReplyToId: Option[Id],
      spoilerText: Option[String],
      sensitive: Option[Boolean],
      language: Option[String],
      mediaIds: Option[List[Id]],
      quotedStatusId: Option[Id],
      quoteApprovalPolicy: Option[QuoteApprovalPolicy],
      scheduledAt: Option[Instant]
  ): Json =
    val required = List(
      "status"     -> Json.fromString(text),
      "visibility" -> Json.fromString(visibility.wire)
    )
    val optional =
      inReplyToId.toList.map(id => "in_reply_to_id" -> Json.fromString(id.value)) ++
        spoilerText.filter(_.nonEmpty).toList.map(text => "spoiler_text" -> Json.fromString(text)) ++
        sensitive.toList.map(value => "sensitive" -> Json.fromBoolean(value)) ++
        language.filter(_.nonEmpty).toList.map(value => "language" -> Json.fromString(value)) ++
        mediaIds.toList.map(ids => "media_ids" -> Json.arr(ids.map(id => Json.fromString(id.value))*)) ++
        quotedStatusId.toList.map(id => "quoted_status_id" -> Json.fromString(id.value)) ++
        quoteApprovalPolicy.toList.map(policy => "quote_approval_policy" -> Json.fromString(policy.wire)) ++
        scheduledAt.toList.map(value => "scheduled_at" -> Json.fromString(value.toString))
    Json.fromFields(required ++ optional)

  private[api] def editBody(
      text: Option[String],
      spoilerText: Option[String],
      sensitive: Option[Boolean],
      language: Option[String],
      mediaIds: Option[List[Id]],
      quoteApprovalPolicy: Option[QuoteApprovalPolicy]
  ): Json =
    val optional =
      text.toList.map(value => "status" -> Json.fromString(value)) ++
        spoilerText.toList.map(value => "spoiler_text" -> Json.fromString(value)) ++
        sensitive.toList.map(value => "sensitive" -> Json.fromBoolean(value)) ++
        language.filter(_.nonEmpty).toList.map(value => "language" -> Json.fromString(value)) ++
        mediaIds.toList.map(ids => "media_ids" -> Json.arr(ids.map(id => Json.fromString(id.value))*)) ++
        quoteApprovalPolicy.toList.map(policy => "quote_approval_policy" -> Json.fromString(policy.wire))
    Json.fromFields(optional)

private final class LiveStatuses[F[_]](runner: EndpointRunner[F])(using effect: MonadThrow[F])
    extends Statuses[F]:

  def fetch(id: Id): F[Status] =
    runner.run(Statuses.fetchEndpoint(id))

  def post(
      text: String,
      visibility: Visibility,
      inReplyToId: Option[Id],
      spoilerText: Option[String],
      sensitive: Option[Boolean],
      language: Option[String],
      mediaIds: Option[List[Id]],
      quotedStatusId: Option[Id],
      quoteApprovalPolicy: Option[QuoteApprovalPolicy],
      scheduledAt: Option[Instant]
  ): F[StatusPostResult] =
    runner.run(
      Statuses.postEndpoint(
        text,
        visibility,
        inReplyToId,
        spoilerText,
        sensitive,
        language,
        mediaIds,
        quotedStatusId,
        quoteApprovalPolicy,
        scheduledAt
      )
    )

  def edit(
      id: Id,
      text: Option[String],
      spoilerText: Option[String],
      sensitive: Option[Boolean],
      language: Option[String],
      mediaIds: Option[List[Id]],
      quoteApprovalPolicy: Option[QuoteApprovalPolicy]
  ): F[Status] =
    runner.run(
      Statuses.editEndpoint(id, text, spoilerText, sensitive, language, mediaIds, quoteApprovalPolicy)
    )

  def delete(id: Id): F[Unit] =
    runner.run(Statuses.deleteEndpoint(id))

  def context(id: Id): F[(Context, Option[AsyncRefresh])] =
    effect.map(runner.runWithResponse(Statuses.contextEndpoint(id))) { case (context, response) =>
      context -> response.header("Mastodon-Async-Refresh").flatMap(Statuses.parseAsyncRefresh)
    }

  def card(id: Id): F[Option[PreviewCard]] =
    effect.map(fetch(id))(_.card)

  def favouritedBy(id: Id, limit: Option[Int]): F[List[Account]] =
    runner.run(Statuses.favouritedByEndpoint(id, limit))

  def rebloggedBy(id: Id, limit: Option[Int]): F[List[Account]] =
    runner.run(Statuses.rebloggedByEndpoint(id, limit))

  def favourite(id: Id): F[Status] =
    runner.run(Statuses.favouriteEndpoint(id))

  def unfavourite(id: Id): F[Status] =
    runner.run(Statuses.unfavouriteEndpoint(id))

  def reblog(id: Id, visibility: Option[Visibility]): F[Status] =
    runner.run(Statuses.reblogEndpoint(id, visibility))

  def unreblog(id: Id): F[Status] =
    runner.run(Statuses.unreblogEndpoint(id))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Statuses[G] =
    new LiveStatuses[G](runner.mapK(transform))
