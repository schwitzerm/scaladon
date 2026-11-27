package ca.schwitzer.scaladon.domain

/** Why the server suggested an account to follow (Mastodon 4.3+, API version 9).
  *
  * The older scalar `source` field is deprecated on the wire and is deliberately not modelled:
  * the adapter targets the latest API only, and `sources` is the field the current serializer
  * always emits. Unknown values fail decoding rather than leaking an unhandled string (legacy #9).
  */
enum SuggestionSource(val wire: String):
  case Featured                 extends SuggestionSource("featured")
  case MostFollowed             extends SuggestionSource("most_followed")
  case MostInteractions         extends SuggestionSource("most_interactions")
  case SimilarToRecentlyFollowed extends SuggestionSource("similar_to_recently_followed")
  case FriendsOfFriends         extends SuggestionSource("friends_of_friends")

object SuggestionSource:
  def all: List[SuggestionSource] = values.toList

  /** Total inverse of [[SuggestionSource.wire]]; `None` for values this version does not know. */
  def fromWire(value: String): Option[SuggestionSource] = all.find(_.wire == value)

/** One entry of `GET /api/v2/suggestions`: an account and every reason it was suggested.
  *
  * `sources` is a list because the server can name several reasons for the same account; the
  * deprecated scalar `source` is ignored.
  */
final case class Suggestion(sources: List[SuggestionSource], account: Account)
