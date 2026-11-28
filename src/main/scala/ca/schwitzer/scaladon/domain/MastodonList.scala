package ca.schwitzer.scaladon.domain

/** Which replies are shown in a list's timeline (`List.replies_policy`, Mastodon 3.3+).
  *
  * Named so the cases do not collide with Scala's own collections: `list` is [[ListMembers]] and
  * `none` is [[NoReplies]]. An enum with a total `fromWire`, like [[Visibility]].
  */
enum RepliesPolicy(val wire: String):
  case Followed    extends RepliesPolicy("followed")
  case ListMembers extends RepliesPolicy("list")
  case NoReplies   extends RepliesPolicy("none")

object RepliesPolicy:
  def all: List[RepliesPolicy] = values.toList

  /** Total inverse of [[RepliesPolicy.wire]]; `None` for values this version does not know. */
  def fromWire(value: String): Option[RepliesPolicy] = all.find(_.wire == value)

/** A user-curated list of followed accounts.
  *
  * Named `MastodonList` to avoid clashing with `scala.List`, since `F[List[MastodonList]]` is the
  * shape of `GET /api/v1/lists`. `exclusive` (Mastodon 4.2) removes list members from the home
  * feed; servers that predate it omit the field and the codec defaults it to `false`.
  */
final case class MastodonList(
    id: Id,
    title: String,
    repliesPolicy: RepliesPolicy,
    exclusive: Boolean
)
