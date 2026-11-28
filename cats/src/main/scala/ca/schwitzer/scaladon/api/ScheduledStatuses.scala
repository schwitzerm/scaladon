package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import java.time.Instant

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, ScheduledStatus}
import ca.schwitzer.scaladon.http.HttpMethod

/** The "Scheduled statuses" section (`/api/v1/scheduled_statuses`), as an algebra of values.
  *
  * A scheduled status is the server-side copy of a `POST /api/v1/statuses` whose `scheduled_at` was
  * in the future. It can be listed with cursor pagination, read, moved to a new time, or cancelled;
  * its contents cannot be edited — cancel and schedule again for that. `reschedule` sends form data
  * because the API documents `scheduled_at` as a form parameter, not a JSON body. `F` is the
  * effect; failures are raised as `ApiException(ApiError)`.
  */
trait ScheduledStatuses[F[_]]:

  /** `GET /api/v1/scheduled_statuses` (`read:statuses`), newest first.
    *
    * `maxId` is an exclusive upper bound, `sinceId` and `minId` lower bounds; `limit` is capped by
    * the server (40). Passing nothing sends no query string at all.
    */
  def list(
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[ScheduledStatus]]

  /** `GET /api/v1/scheduled_statuses/{id}` (`read:statuses`); 404 when it is not yours. */
  def get(id: Id): F[ScheduledStatus]

  /** `PUT /api/v1/scheduled_statuses/{id}` (`write:statuses`) with `scheduled_at` as form data.
    *
    * The server requires the new time to be at least five minutes in the future; that rule stays
    * with the server and surfaces as `ApiError.Http(422, …)` rather than being re-implemented here.
    */
  def reschedule(id: Id, scheduledAt: Instant): F[ScheduledStatus]

  /** `DELETE /api/v1/scheduled_statuses/{id}` (`write:statuses`); the response body is empty. */
  def cancel(id: Id): F[Unit]

  def mapK[G[_]: MonadThrow](transform: F ~> G): ScheduledStatuses[G]

object ScheduledStatuses:

  def live[F[_]](runner: EndpointRunner[F]): ScheduledStatuses[F] =
    new LiveScheduledStatuses[F](runner)

  def listEndpoint(
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): Endpoint[List[ScheduledStatus]] =
    Endpoint.get[List[ScheduledStatus]]("/api/v1/scheduled_statuses", pageQuery(maxId, sinceId, minId, limit))

  def getEndpoint(id: Id): Endpoint[ScheduledStatus] =
    Endpoint.get[ScheduledStatus](path(id))

  /** `PUT` with a form body carrying the new `scheduled_at`. */
  def rescheduleEndpoint(id: Id, scheduledAt: Instant): Endpoint[ScheduledStatus] =
    Endpoint.putForm[ScheduledStatus](path(id), List("scheduled_at" -> scheduledAt.toString))

  /** `DELETE` with no body; the runner treats the empty 200 as `{}`. */
  def cancelEndpoint(id: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, path(id))

  private def path(id: Id): String = s"/api/v1/scheduled_statuses/${id.value}"

  /** Documented order: `max_id`, `since_id`, `min_id`, `limit`; absent options are omitted. */
  private def pageQuery(
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): List[(String, String)] =
    maxId.toList.map(id => "max_id" -> id.value) ++
      sinceId.toList.map(id => "since_id" -> id.value) ++
      minId.toList.map(id => "min_id" -> id.value) ++
      limit.toList.map(value => "limit" -> value.toString)

private final class LiveScheduledStatuses[F[_]](runner: EndpointRunner[F]) extends ScheduledStatuses[F]:

  def list(
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[ScheduledStatus]] =
    runner.run(ScheduledStatuses.listEndpoint(maxId, sinceId, minId, limit))

  def get(id: Id): F[ScheduledStatus] =
    runner.run(ScheduledStatuses.getEndpoint(id))

  def reschedule(id: Id, scheduledAt: Instant): F[ScheduledStatus] =
    runner.run(ScheduledStatuses.rescheduleEndpoint(id, scheduledAt))

  def cancel(id: Id): F[Unit] =
    runner.run(ScheduledStatuses.cancelEndpoint(id))

  def mapK[G[_]: MonadThrow](transform: F ~> G): ScheduledStatuses[G] =
    new LiveScheduledStatuses[G](runner.mapK(transform))
