package ca.schwitzer.scaladon.domain

import java.time.Instant

/** Where a filter group applies.
  *
  * An enum with a total `fromWire`, like [[Visibility]]: the wire vocabulary is closed
  * in API version 12, and an unknown value fails decoding rather than leaking a raw string.
  */
enum FilterContext(val wire: String):
  case Home          extends FilterContext("home")
  case Notifications extends FilterContext("notifications")
  case Public        extends FilterContext("public")
  case Thread        extends FilterContext("thread")
  case Account       extends FilterContext("account")

object FilterContext:
  def all: List[FilterContext] = values.toList

  /** Total inverse of [[FilterContext.wire]]; `None` for values this version does not know. */
  def fromWire(value: String): Option[FilterContext] = all.find(_.wire == value)

/** What the server does with a status that matches a filter group.
  *
  * The Filter entity documentation says clients should treat an unknown action as `warn`; this
  * adapter instead fails decoding, because the vocabulary is closed in API version 12 (`blur` was
  * the last addition, Mastodon 4.4 / API version 5) and a silent downgrade would hide a mismatch
  * from the caller.
  */
enum FilterAction(val wire: String):
  case Warn extends FilterAction("warn")
  case Hide extends FilterAction("hide")
  case Blur extends FilterAction("blur")

object FilterAction:
  def all: List[FilterAction] = values.toList

  /** Total inverse of [[FilterAction.wire]]; `None` for values this version does not know. */
  def fromWire(value: String): Option[FilterAction] = all.find(_.wire == value)

/** A keyword belonging to a [[Filter]] group (server-side filters, API version 4+).
  *
  * `wholeWord` decides whether the keyword matches only on word boundaries.
  */
final case class FilterKeyword(id: Id, keyword: String, wholeWord: Boolean)

/** A status belonging to a [[Filter]] group: its id in the filter plus the filtered status id. */
final case class FilterStatus(id: Id, statusId: Id)

/** A user-defined filter group, matched server-side since Mastodon 4.0.
  *
  * `keywords` and `statuses` are optional on the wire: they are omitted when the filter is
  * embedded in a `FilterResult` on a status. Absent therefore decodes to `Nil` rather than failing,
  * and `expiresAt` is `None` for a filter that never expires.
  */
final case class Filter(
    id: Id,
    title: String,
    context: List[FilterContext],
    expiresAt: Option[Instant],
    filterAction: FilterAction,
    keywords: List[FilterKeyword],
    statuses: List[FilterStatus]
)
