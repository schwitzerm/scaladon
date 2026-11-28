package ca.schwitzer.scaladon.domain

import java.time.Instant

/** One name/value metadata pair on a profile (`Account.fields` / `Profile.fields`).
  *
  * `verified_at` is only set when the value is a verified `rel="me"` link, so it is optional on the
  * wire and here. The field keeps the HTML Mastodon rendered for `value`; the raw text lives on
  * `AccountSource`/`Profile`.
  */
final case class AccountField(name: String, value: String, verifiedAt: Option[Instant])

/** A public role badge on an account (the simplified Role entity).
  *
  * Only the fields a client can render are modelled; permissions and highlighting live on the
  * full Role entity, which `verify_credentials` returns separately.
  */
final case class AccountRole(id: Id, name: String, color: String)

/** A Mastodon account, as of API v12.
  *
  * This is the current shape, not the 2017 one: `url` and `uri` may be absent (remote/suspended
  * accounts), `discoverable`/`hide_collections` are tri-state (`Option`), and the server publishes
  * counts and `fields` for every account that a profile view renders. `moved` points at another
  * account, which is why the decoder is recursive. Attributes a server only emits in a given state
  * (`suspended`, `limited`, `memorial`, `noindex`) stay `Option` so "absent" is not confused with
  * `false`; booleans that are always expected default to `false` when absent.
  *
  * `last_status_at` is a calendar date on the wire (Mastodon 3.1+), decoded to midnight UTC.
  */
final case class Account(
    id: Id,
    username: String,
    acct: String,
    displayName: String,
    note: String,
    url: Option[String],
    uri: Option[String],
    avatar: String,
    avatarStatic: Option[String],
    avatarDescription: Option[String],
    header: String,
    headerStatic: Option[String],
    headerDescription: Option[String],
    locked: Boolean,
    bot: Boolean,
    group: Boolean,
    discoverable: Option[Boolean],
    indexable: Boolean,
    noindex: Option[Boolean],
    moved: Option[Account],
    memorial: Option[Boolean],
    suspended: Option[Boolean],
    limited: Option[Boolean],
    createdAt: Instant,
    lastStatusAt: Option[Instant],
    statusesCount: Int,
    followersCount: Int,
    followingCount: Int,
    hideCollections: Option[Boolean],
    showMedia: Boolean,
    showMediaReplies: Boolean,
    showFeatured: Boolean,
    fields: List[AccountField],
    invalidHandle: Option[Boolean]
)

/** The private `source` block only the authenticated user sees on `CredentialAccount`.
  *
  * It carries the plain-text bio and fields that `update_credentials`/`PATCH /api/v1/profile`
  * expect back, plus the user's posting defaults. `privacy` reuses [[Visibility]] because the wire
  * values are identical. `quote_policy` is kept as an untyped string: it is a different enum from
  * visibility and is not used by any ported endpoint yet.
  */
final case class AccountSource(
    privacy: Visibility,
    sensitive: Boolean,
    spoilerField: Boolean,
    language: Option[String],
    note: String,
    fields: List[AccountField],
    followRequestsCount: Int,
    hideCollections: Option[Boolean],
    discoverable: Option[Boolean],
    indexable: Boolean,
    attributionDomains: List[String],
    quotePolicy: Option[String]
)

/** What `verify_credentials` (and profile mutations) return: an [[Account]] plus the extras that
  * are only visible to the account owner — the private `source`, the authorized user's `role`, and
  * (Mastodon 5.0) the sign-up [[AccountState]].
  */
final case class CredentialAccount(
    account: Account,
    source: AccountSource,
    role: Option[AccountRole],
    state: Option[AccountState]
)
