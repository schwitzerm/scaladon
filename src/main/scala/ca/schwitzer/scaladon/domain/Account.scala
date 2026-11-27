package ca.schwitzer.scaladon.domain

import java.time.Instant

/** A Mastodon account. Only the fields the refactor has ported so far are modelled; see
  * `legacy/src/main/scala/ca/schwitzer/scaladon/models/Account.scala` for the full 2017 shape.
  */
final case class Account(
    id: Id,
    username: String,
    acct: String,
    displayName: String,
    note: String,
    url: String,
    avatar: String,
    header: String,
    locked: Boolean,
    createdAt: Instant
)
