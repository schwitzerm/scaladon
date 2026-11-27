package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, Notification}
import ca.schwitzer.scaladon.http.HttpMethod

/** The "Notifications" section, limited to the v1 list/get/clear surface.
  *
  * `supportedTypes` is what opts a client into [[ca.schwitzer.scaladon.domain.NotificationFallback]]:
  * it names the kinds the client renders itself, and the server fills `fallback` for every other
  * kind it can describe. Grouped v2 notifications are a separate, not-yet-ported feature.
  */
trait Notifications[F[_]]:

  /** `GET /api/v1/notifications`; repeated `types[]`/`exclude_types[]`/`supported_types[]` keys. */
  def list(
      types: List[String] = Nil,
      excludeTypes: List[String] = Nil,
      accountId: Option[Id] = None,
      supportedTypes: List[String] = Nil,
      limit: Option[Int] = None,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None
  ): F[List[Notification]]

  /** `GET /api/v1/notifications/{id}`. */
  def get(id: Id): F[Notification]

  /** `POST /api/v1/notifications/clear`; the server answers with an empty object. */
  def clear(): F[Unit]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Notifications[G]

object Notifications:

  def live[F[_]](runner: EndpointRunner[F]): Notifications[F] =
    new LiveNotifications[F](runner)

  /** `GET /api/v1/notifications`. */
  def listEndpoint(
      types: List[String],
      excludeTypes: List[String],
      accountId: Option[Id],
      supportedTypes: List[String],
      limit: Option[Int],
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id]
  ): Endpoint[List[Notification]] =
    Endpoint.get[List[Notification]](
      "/api/v1/notifications",
      listQuery(types, excludeTypes, accountId, supportedTypes, limit, maxId, sinceId, minId)
    )

  /** `GET /api/v1/notifications/{id}`. */
  def getEndpoint(id: Id): Endpoint[Notification] =
    Endpoint.get[Notification](s"/api/v1/notifications/${id.value}")

  /** `POST /api/v1/notifications/clear` — answers with `{}`. */
  def clearEndpoint: Endpoint[Unit] =
    Endpoint.unit(HttpMethod.POST, "/api/v1/notifications/clear")

  /** Array parameters repeat the bracketed key, as the Mastodon API expects. */
  private def listQuery(
      types: List[String],
      excludeTypes: List[String],
      accountId: Option[Id],
      supportedTypes: List[String],
      limit: Option[Int],
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id]
  ): List[(String, String)] =
    types.map(value => "types[]" -> value) ++
      excludeTypes.map(value => "exclude_types[]" -> value) ++
      accountId.toList.map(value => "account_id" -> value.value) ++
      supportedTypes.map(value => "supported_types[]" -> value) ++
      limit.toList.map(value => "limit" -> value.toString) ++
      maxId.toList.map(value => "max_id" -> value.value) ++
      sinceId.toList.map(value => "since_id" -> value.value) ++
      minId.toList.map(value => "min_id" -> value.value)

private final class LiveNotifications[F[_]](runner: EndpointRunner[F]) extends Notifications[F]:

  def list(
      types: List[String],
      excludeTypes: List[String],
      accountId: Option[Id],
      supportedTypes: List[String],
      limit: Option[Int],
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id]
  ): F[List[Notification]] =
    runner.run(
      Notifications.listEndpoint(
        types,
        excludeTypes,
        accountId,
        supportedTypes,
        limit,
        maxId,
        sinceId,
        minId
      )
    )

  def get(id: Id): F[Notification] =
    runner.run(Notifications.getEndpoint(id))

  def clear(): F[Unit] =
    runner.run(Notifications.clearEndpoint)

  def mapK[G[_]: MonadThrow](transform: F ~> G): Notifications[G] =
    new LiveNotifications[G](runner.mapK(transform))
