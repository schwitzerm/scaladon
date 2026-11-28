package ca.schwitzer.scaladon.domain

import java.time.Instant

/** A raw profile field, as exposed by `GET /api/v1/profile` under `fields`.
  *
  * Unlike [[AccountField]], the value is the unprocessed text the user typed, not rendered HTML.
  */
final case class ProfileField(name: String, value: String, verifiedAt: Option[Instant])

/** The current user's editable profile (`GET`/`PATCH /api/v1/profile`, Mastodon 4.6+).
  *
  * This is the source-of-truth view for editing: `note` and `fields` are plain text, `avatar` and
  * `header` are nullable (unset rather than "missing" placeholders), and the profile-visibility
  * flags say which tabs the profile shows. `formattedNote`/`formattedFields` hold the rendered HTML
  * when the server includes it; absent means the caller should render from the raw values.
  */
final case class Profile(
    id: Id,
    displayName: String,
    note: String,
    fields: List[ProfileField],
    avatar: Option[String],
    avatarStatic: Option[String],
    avatarDescription: String,
    header: Option[String],
    headerStatic: Option[String],
    headerDescription: String,
    locked: Boolean,
    bot: Boolean,
    hideCollections: Option[Boolean],
    discoverable: Option[Boolean],
    indexable: Boolean,
    showMedia: Boolean,
    showMediaReplies: Boolean,
    showFeatured: Boolean,
    attributionDomains: List[String],
    formattedNote: Option[String],
    formattedFields: List[ProfileField]
)
