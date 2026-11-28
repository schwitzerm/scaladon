package ca.schwitzer.scaladon.domain

import java.time.Instant

/** A status ("toot").
  *
  * `reblog` is recursive, as in the API; a boost embeds the status it boosts. `quote` is likewise
  * recursive since the quoted status may itself carry a quote (clients are advised not to render
  * more than one level). Absent optional fields are `None`; an empty `spoiler_text` is normalised
  * to `None`, since the wire can carry both `""` and `null` for the same absence.
  *
  * The `bookmarked`, `muted`, `pinned` booleans are only meaningful when the request carried a
  * user token, and default to `false` when the server omits them. `editedAt` is `Some` once a
  * status has been edited (Mastodon 3.5+). `card`, `language`, `text` and `quote` cover the
  * 4.4-5.0 surface; `repliesCount`/`quotesCount` default to `0` for servers that predate them.
  * `poll` is the poll attached to the status, absent when the status has none.
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
    repliesCount: Int,
    reblogsCount: Int,
    favouritesCount: Int,
    quotesCount: Int,
    favourited: Boolean,
    reblogged: Boolean,
    bookmarked: Boolean,
    muted: Boolean,
    pinned: Boolean,
    editedAt: Option[Instant],
    language: Option[String],
    text: Option[String],
    card: Option[PreviewCard],
    quote: Option[Quote],
    poll: Option[Poll]
)
