package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{GroupedNotifications, Id, Notification, UnreadCount}
import ca.schwitzer.scaladon.http.HttpMethod

/** The "Notifications" section: the v1 list/get/clear surface plus the grouped v2 endpoints.
  *
  * `supportedTypes` is what opts a client into [[ca.schwitzer.scaladon.domain.NotificationFallback]]:
  * it names the kinds the client renders itself, and the server fills `fallback` for every other
  * kind it can describe. On the v1 endpoints it applies to `list` and `get`; on v2 it applies to
  * `grouped` and `group`.
  *
  * The v2 page is deduplicated: `accounts` and `statuses` are hoisted into
  * [[ca.schwitzer.scaladon.domain.GroupedNotifications]], and each
  * [[ca.schwitzer.scaladon.domain.NotificationGroup]] references them by id.
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

  /** `GET /api/v1/notifications/{id}`; `supported_types[]` opts into the `fallback` attribute. */
  def get(id: Id, supportedTypes: List[String] = Nil): F[Notification]

  /** `POST /api/v1/notifications/clear`; the server answers with an empty object. */
  def clear(): F[Unit]

  /** `GET /api/v2/notifications`; repeated `types[]`/`exclude_types[]`/`supported_types[]` keys. */
  def grouped(
      types: List[String] = Nil,
      excludeTypes: List[String] = Nil,
      supportedTypes: List[String] = Nil,
      limit: Option[Int] = None
  ): F[GroupedNotifications]

  /** `GET /api/v2/notifications/{group_key}`; returns a page with that one group. */
  def group(groupKey: String, supportedTypes: List[String] = Nil): F[GroupedNotifications]

  /** `POST /api/v2/notifications/{group_key}/dismiss`; the server answers with an empty object. */
  def dismissGroup(groupKey: String): F[Unit]

  /** `GET /api/v2/notifications/unread_count`; the body is `{"count": n}`. */
  def unreadCount(): F[UnreadCount]

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
  def getEndpoint(id: Id, supportedTypes: List[String] = Nil): Endpoint[Notification] =
    Endpoint.get[Notification](
      s"/api/v1/notifications/${id.value}",
      supportedTypesQuery(supportedTypes)
    )

  /** `POST /api/v1/notifications/clear` — answers with `{}`. */
  def clearEndpoint: Endpoint[Unit] =
    Endpoint.unit(HttpMethod.POST, "/api/v1/notifications/clear")

  /** `GET /api/v2/notifications`. */
  def groupedEndpoint(
      types: List[String],
      excludeTypes: List[String],
      supportedTypes: List[String],
      limit: Option[Int]
  ): Endpoint[GroupedNotifications] =
    Endpoint.get[GroupedNotifications](
      "/api/v2/notifications",
      types.map(value => "types[]" -> value) ++
        excludeTypes.map(value => "exclude_types[]" -> value) ++
        supportedTypesQuery(supportedTypes) ++
        limit.toList.map(value => "limit" -> value.toString)
    )

  /** `GET /api/v2/notifications/{group_key}`. */
  def groupEndpoint(
      groupKey: String,
      supportedTypes: List[String] = Nil
  ): Endpoint[GroupedNotifications] =
    Endpoint.get[GroupedNotifications](
      s"/api/v2/notifications/${groupKey}",
      supportedTypesQuery(supportedTypes)
    )

  /** `POST /api/v2/notifications/{group_key}/dismiss` — answers with `{}`. */
  def dismissGroupEndpoint(groupKey: String): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.POST, s"/api/v2/notifications/${groupKey}/dismiss")

  /** `GET /api/v2/notifications/unread_count` — answers with `{"count": n}`. */
  def unreadCountEndpoint: Endpoint[UnreadCount] =
    Endpoint.get[UnreadCount]("/api/v2/notifications/unread_count")

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
      supportedTypesQuery(supportedTypes) ++
      limit.toList.map(value => "limit" -> value.toString) ++
      maxId.toList.map(value => "max_id" -> value.value) ++
      sinceId.toList.map(value => "since_id" -> value.value) ++
      minId.toList.map(value => "min_id" -> value.value)

  private def supportedTypesQuery(supportedTypes: List[String]): List[(String, String)] =
    supportedTypes.map(value => "supported_types[]" -> value)

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

  def get(id: Id, supportedTypes: List[String]): F[Notification] =
    runner.run(Notifications.getEndpoint(id, supportedTypes))

  def clear(): F[Unit] =
    runner.run(Notifications.clearEndpoint)

  def grouped(
      types: List[String],
      excludeTypes: List[String],
      supportedTypes: List[String],
      limit: Option[Int]
  ): F[GroupedNotifications] =
    runner.run(Notifications.groupedEndpoint(types, excludeTypes, supportedTypes, limit))

  def group(groupKey: String, supportedTypes: List[String]): F[GroupedNotifications] =
    runner.run(Notifications.groupEndpoint(groupKey, supportedTypes))

  def dismissGroup(groupKey: String): F[Unit] =
    runner.run(Notifications.dismissGroupEndpoint(groupKey))

  def unreadCount(): F[UnreadCount] =
    runner.run(Notifications.unreadCountEndpoint)

  def mapK[G[_]: MonadThrow](transform: F ~> G): Notifications[G] =
    new LiveNotifications[G](runner.mapK(transform))