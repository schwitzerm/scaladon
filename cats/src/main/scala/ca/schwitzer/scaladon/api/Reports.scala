package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, Report, ReportCategory}

/** The "Reports" section: filing reports for moderators.
  *
  * Only the documented write endpoint is modelled: the current API has no list/read endpoint, so
  * `GET /api/v1/reports` is deliberately not exposed. Filing needs a user token
  * (`write:reports`); failures are raised inside `F` as `ApiException(ApiError)`.
  *
  * `POST` takes form data, as the API documents it, so every array is sent as a repeated
  * `status_ids[]`/`rule_ids[]` key and absent options are omitted rather than sent as `null`. `category` defaults to `other` server-side, and providing `ruleIds` makes the
  * server treat the report as a `violation` regardless of the category sent.
  */
trait Reports[F[_]]:

  /** `POST /api/v1/reports`; returns the report as the server stored it. */
  def create(
      accountId: Id,
      statusIds: List[Id] = Nil,
      comment: Option[String] = None,
      category: Option[ReportCategory] = None,
      forward: Option[Boolean] = None,
      ruleIds: List[Id] = Nil
  ): F[Report]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Reports[G]

object Reports:

  def live[F[_]](runner: EndpointRunner[F]): Reports[F] =
    new LiveReports[F](runner)

  /** `POST /api/v1/reports`; form-encoded, with repeated array keys. */
  def createEndpoint(
      accountId: Id,
      statusIds: List[Id],
      comment: Option[String],
      category: Option[ReportCategory],
      forward: Option[Boolean],
      ruleIds: List[Id]
  ): Endpoint[Report] =
    Endpoint.postForm[Report]("/api/v1/reports", createFields(accountId, statusIds, comment, category, forward, ruleIds))

  private def createFields(
      accountId: Id,
      statusIds: List[Id],
      comment: Option[String],
      category: Option[ReportCategory],
      forward: Option[Boolean],
      ruleIds: List[Id]
  ): List[(String, String)] =
    List("account_id" -> accountId.value) ++
      statusIds.map(id => "status_ids[]" -> id.value) ++
      comment.toList.map(value => "comment" -> value) ++
      category.toList.map(value => "category" -> value.wire) ++
      forward.toList.map(value => "forward" -> value.toString) ++
      ruleIds.map(id => "rule_ids[]" -> id.value)

private final class LiveReports[F[_]](runner: EndpointRunner[F]) extends Reports[F]:

  def create(
      accountId: Id,
      statusIds: List[Id],
      comment: Option[String],
      category: Option[ReportCategory],
      forward: Option[Boolean],
      ruleIds: List[Id]
  ): F[Report] =
    runner.run(Reports.createEndpoint(accountId, statusIds, comment, category, forward, ruleIds))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Reports[G] =
    new LiveReports[G](runner.mapK(transform))
