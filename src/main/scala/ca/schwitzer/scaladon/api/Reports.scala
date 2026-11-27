package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, Report, ReportCategory}

/** The "Reports" section: filing reports for moderators and reading back the ones already filed.
  *
  * Both calls need a user token (`write:reports`; `read:reports` for the list). Expected failures
  * are raised inside `F` as `ApiException(ApiError)`.
  *
  * `POST` takes form data, as the API documents it, so every array is sent as a repeated
  * `status_ids[]`/`rule_ids[]` key and absent options are omitted rather than sent as `null`
  * (legacy #10). `category` defaults to `other` server-side, and providing `ruleIds` makes the
  * server treat the report as a `violation` regardless of the category sent.
  */
trait Reports[F[_]]:

  /** `GET /api/v1/reports`, paginated. The current API documentation only covers the write side;
    * this is the read counterpart ported from 0.3.2 and a server that does not route it answers
    * `404`, which surfaces as `ApiError.Http` with `isNotFound`.
    */
  def list(
      limit: Option[Int] = None,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None
  ): F[List[Report]]

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

  /** `GET /api/v1/reports`; a bare JSON array of reports. */
  def listEndpoint(
      limit: Option[Int],
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id]
  ): Endpoint[List[Report]] =
    Endpoint.get[List[Report]](
      "/api/v1/reports",
      query = pagination(limit, maxId, sinceId, minId)
    )

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

  private def pagination(
      limit: Option[Int],
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id]
  ): List[(String, String)] =
    limit.toList.map(value => "limit" -> value.toString) ++
      maxId.toList.map(id => "max_id" -> id.value) ++
      sinceId.toList.map(id => "since_id" -> id.value) ++
      minId.toList.map(id => "min_id" -> id.value)

private final class LiveReports[F[_]](runner: EndpointRunner[F]) extends Reports[F]:

  def list(
      limit: Option[Int],
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id]
  ): F[List[Report]] =
    runner.run(Reports.listEndpoint(limit, maxId, sinceId, minId))

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
