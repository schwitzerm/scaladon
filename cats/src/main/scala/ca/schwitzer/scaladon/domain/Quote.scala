package ca.schwitzer.scaladon.domain

import cats.Show

/** How a quote is authorised, as reported by the quote entities.
  *
  * Mastodon 4.5 added `blocked_account`, `blocked_domain` and `muted_account`; `deleted` is the
  * state of an accepted quote whose target has since been deleted. The docs say unknown values
  * should be treated as `unauthorized`; decoding fails instead so a new wire value is noticed
  * rather than silently misrendered.
  */
enum QuoteState(val wire: String):
  case Pending        extends QuoteState("pending")
  case Accepted       extends QuoteState("accepted")
  case Rejected       extends QuoteState("rejected")
  case Revoked        extends QuoteState("revoked")
  case Deleted        extends QuoteState("deleted")
  case Unauthorized   extends QuoteState("unauthorized")
  case BlockedAccount extends QuoteState("blocked_account")
  case BlockedDomain  extends QuoteState("blocked_domain")
  case MutedAccount   extends QuoteState("muted_account")

object QuoteState:
  def all: List[QuoteState] = values.toList

  /** Total inverse of [[QuoteState.wire]]; `None` for unknown values. */
  def fromWire(value: String): Option[QuoteState] = all.find(_.wire == value)

  given Show[QuoteState] = Show.show(_.wire)

/** The policy a status sets for who may quote it (Mastodon 4.5).
  *
  * `private` and `direct` statuses always report `nobody` regardless of what was requested.
  */
enum QuoteApprovalPolicy(val wire: String):
  case Public    extends QuoteApprovalPolicy("public")
  case Followers extends QuoteApprovalPolicy("followers")
  case Nobody    extends QuoteApprovalPolicy("nobody")

object QuoteApprovalPolicy:
  def all: List[QuoteApprovalPolicy] = values.toList

  def fromWire(value: String): Option[QuoteApprovalPolicy] = all.find(_.wire == value)

  given Show[QuoteApprovalPolicy] = Show.show(_.wire)

/** A full quote, carrying the quoted status whenever the server is allowed to include it.
  *
  * `quotedStatusId` is populated for the shallow-shaped payloads (and for deleted-status
  * responses), so a single type covers both `Quote` and `ShallowQuote` wire forms on `Status`.
  */
final case class Quote(
    state: QuoteState,
    quotedStatus: Option[Status],
    quotedStatusId: Option[Id]
)

/** A quote placeholder that names the quoted status without embedding it. */
final case class ShallowQuote(state: QuoteState, quotedStatusId: Option[Id])
