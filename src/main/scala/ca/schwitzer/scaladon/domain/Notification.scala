package ca.schwitzer.scaladon.domain

import java.time.Instant

/** A server-rendered stand-in for a notification type the client does not understand.
  *
  * Mastodon 4.6 sends this only when the request names its `supported_types`. It exists so clients
  * can show *something* for newly introduced notification kinds without shipping a new release.
  */
final case class NotificationFallback(
    title: String,
    summary: Option[String],
    details: Option[String]
)

/** A notification of an event relevant to the user.
  *
  * `kind` is a plain `String`, deliberately not an enum: Mastodon adds notification types every
  * release (4.5 added `quote`, 4.6 `added_to_collection`), and a closed enum would turn a new type
  * into a decode failure. When a type is unknown, `fallback` carries a server-rendered description
  * instead. Callers that care about a specific type compare against a literal.
  *
  * Only the nested entities this adapter models are decoded: `status`, `collection`, and the
  * acting `account`. Report, severance-event and moderation-warning payloads are ignored; their
  * notification kinds still decode.
  */
final case class Notification(
    id: Id,
    kind: String,
    groupKey: Option[String],
    createdAt: Instant,
    account: Account,
    status: Option[Status],
    collection: Option[Collection],
    fallback: Option[NotificationFallback]
)
