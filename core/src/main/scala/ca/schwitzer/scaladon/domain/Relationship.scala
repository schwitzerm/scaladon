package ca.schwitzer.scaladon.domain

import java.time.Instant

/** The authenticated user's relationship to another account, as returned by the follow/block/mute
  * actions and `GET /api/v1/accounts/relationships`.
  *
  * The booleans are all defaulted to `false` when absent (the server always sends them today, but
  * a missing field must not fail a status-page render). `languages` is nullable on the wire;
  * `muting_expires_at` is only set for timed mutes.
  */
final case class Relationship(
    id: Id,
    following: Boolean,
    showingReblogs: Boolean,
    notifying: Boolean,
    languages: Option[List[String]],
    followedBy: Boolean,
    blocking: Boolean,
    blockedBy: Boolean,
    muting: Boolean,
    mutingNotifications: Boolean,
    mutingExpiresAt: Option[Instant],
    requested: Boolean,
    requestedBy: Boolean,
    domainBlocking: Boolean,
    endorsed: Boolean,
    note: String
)
