package ca.schwitzer.scaladon.domain

import java.time.Instant

/** A custom emoji embedded in an announcement's text.
  *
  * The same entity a status carries; it is modelled here because announcements are the only ported
  * surface that returns it so far.
  */
final case class CustomEmoji(
    shortcode: String,
    url: String,
    staticUrl: String,
    visibleInPicker: Boolean,
    category: Option[String]
)

/** An account mentioned in an announcement's text (`Announcement::Account`).
  *
  * This is a deliberately small shape, not an [[Account]]: the wire entity carries only the fields
  * needed to render a link to the profile.
  */
final case class AnnouncementMention(id: Id, username: String, url: String, acct: String)

/** A status linked from an announcement's text (`Announcement::Status`): id and URL only. */
final case class AnnouncementStatus(id: Id, url: String)

/** One emoji reaction attached to an announcement.
  *
  * `name` is either a Unicode emoji or a custom emoji shortcode; `url` and `staticUrl` are present
  * only for custom emoji. `me` is absent when the request carried no user token and defaults to
  * `false`, matching the other viewer-relative booleans.
  */
final case class AnnouncementReaction(
    name: String,
    count: Int,
    me: Boolean,
    url: Option[String],
    staticUrl: Option[String]
)

/** An announcement set by the server's administration (`GET /api/v1/announcements`).
  *
  * `startsAt`/`endsAt` are nullable scheduling bounds; `allDay` is only meaningful together with
  * them, and is `false` when either is absent. `publishedAt` is required, `updatedAt` is present
  * on every payload Mastodon sends today.
  *
  * `read` is viewer-relative, so it is `Option`: an anonymous or pre-3.1 payload does not carry
  * it. `reactionsAllowed` was added in 5.0 (API version 12); an absent flag decodes to `false`,
  * the conservative reading — a client should not offer reactions a server might reject.
  * `tags` reuse [[ShallowTag]] because the wire shape is exactly `name` + `url`.
  */
final case class Announcement(
    id: Id,
    content: String,
    startsAt: Option[Instant],
    endsAt: Option[Instant],
    allDay: Boolean,
    publishedAt: Instant,
    updatedAt: Instant,
    read: Option[Boolean],
    mentions: List[AnnouncementMention],
    statuses: List[AnnouncementStatus],
    tags: List[ShallowTag],
    emojis: List[CustomEmoji],
    reactions: List[AnnouncementReaction],
    reactionsAllowed: Boolean
)
