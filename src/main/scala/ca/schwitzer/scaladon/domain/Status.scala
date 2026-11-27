package ca.schwitzer.scaladon.domain

import java.time.Instant

/** A status ("toot").
  *
  * `reblog` is recursive, as in the API. Absent optional fields are `None`; an empty `spoiler_text`
  * is normalised to `None` (legacy decoded `""` and `null` inconsistently).
  */
final case class Status(
    id: Id,
    uri: String,
    url: Option[String],
    account: Account,
    content: String,
    createdAt: Instant,
    visibility: Visibility,
    inReplyToId: Option[Id],
    inReplyToAccountId: Option[Id],
    spoilerText: Option[String],
    sensitive: Boolean,
    reblog: Option[Status],
    reblogsCount: Int,
    favouritesCount: Int,
    favourited: Boolean,
    reblogged: Boolean
)
