package ca.schwitzer.scaladon.domain

import java.time.Instant

/** A server-side collapsed group of notifications of the same kind and target.
  *
  * The server groups `favourite`, `follow`, `reblog` and `admin.sign_up` notifications made in a
  * similar timeframe under one opaque `groupKey`. Unlike the v1 notification, the group itself
  * carries no account object: the accounts and statuses it references are hoisted into the
  * [[GroupedNotifications]] root so a page does not repeat them per group.
  *
  * `kind` is again a plain `String` for the same forward-compatibility reason as
  * [[Notification.kind]]; `fallback` is the server-rendered description used when the client does
  * not name the kind in `supported_types`.
  */
final case class NotificationGroup(
    groupKey: String,
    notificationsCount: Int,
    kind: String,
    mostRecentNotificationId: Id,
    pageMinId: Option[Id],
    pageMaxId: Option[Id],
    latestPageNotificationAt: Option[Instant],
    sampleAccountIds: List[Id],
    statusId: Option[Id],
    collection: Option[Collection],
    fallback: Option[NotificationFallback]
)

/** One page of `GET /api/v2/notifications`: the deduplicated entities plus the groups.
  *
  * `accounts` and `statuses` are referenced by `sampleAccountIds` and `statusId` inside each
  * group. `partialAccounts` is not modelled because `expand_accounts=partial_avatars` is not
  * exposed; the default `full` expansion always fills `accounts`.
  */
final case class GroupedNotifications(
    accounts: List[Account],
    statuses: List[Status],
    notificationGroups: List[NotificationGroup]
)

/** The `{ "count": n }` body of the (capped) unread-notification count endpoints. */
final case class UnreadCount(count: Int)