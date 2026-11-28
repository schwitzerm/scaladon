package ca.schwitzer.scaladon.domain

import java.time.Instant

/** The generic reason a [[Report]] was filed, as of API v12 (Mastodon 4.2 added `legal`).
  *
  * An enum with a total `fromWire`, matching [[Visibility]]: the API documents a closed
  * set, so an unknown value fails decoding instead of leaking an untyped string. The wire strings
  * are also what `POST /api/v1/reports` expects back, which is why this type carries `wire`.
  */
enum ReportCategory(val wire: String):
  case Spam      extends ReportCategory("spam")
  case Legal     extends ReportCategory("legal")
  case Violation extends ReportCategory("violation")
  case Other     extends ReportCategory("other")

object ReportCategory:
  def all: List[ReportCategory] = values.toList

  /** Total inverse of [[ReportCategory.wire]]; `None` for values this version does not know. */
  def fromWire(value: String): Option[ReportCategory] = all.find(_.wire == value)

/** A report filed against an account and, optionally, some of its statuses.
  *
  * The three id arrays are nullable on the wire (`rule_ids` is even `null` in the documented
  * example); the codec normalises a missing or `null` array to `Nil`, the same way [[Tag.history]]
  * is normalised. `comment` is a plain string on the wire and an empty one is normalised to
  * `None`. `actionTakenAt` is set only once a moderator has acted, and `forwarded` records whether
  * the report reached a remote instance's moderators.
  */
final case class Report(
    id: Id,
    actionTaken: Boolean,
    actionTakenAt: Option[Instant],
    category: ReportCategory,
    comment: Option[String],
    forwarded: Boolean,
    createdAt: Instant,
    statusIds: List[Id],
    collectionIds: List[Id],
    ruleIds: List[Id],
    targetAccount: Account
)
