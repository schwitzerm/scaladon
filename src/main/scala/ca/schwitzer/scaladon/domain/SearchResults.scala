package ca.schwitzer.scaladon.domain

/** The `type` filter of `GET /api/v2/search`, with the exact strings the API accepts.
  *
  * Only the three categories the endpoint can restrict to are modelled. `collections` (Mastodon
  * 4.6+) appears in search results but has no documented `type` value, so there is nothing to
  * filter by and no case for it.
  */
enum SearchType(val wire: String):
  case Accounts extends SearchType("accounts")
  case Statuses extends SearchType("statuses")
  case Hashtags extends SearchType("hashtags")

/** The `Search` entity: the matching accounts, statuses and hashtags for one query.
  *
  * Search recall is a server capability, not a client one: without an ElasticSearch backend and a
  * user token a server returns accounts and hashtags but no statuses, and an empty list therefore
  * does not prove that nothing matches. The endpoint can also resolve a remote account or a URL
  * before answering; that behaviour is a request flag, not a property of the result.
  *
  * `collections` (Mastodon 4.6+) is deliberately not modelled yet; the decoder ignores the key.
  */
final case class SearchResults(
    accounts: List[Account],
    statuses: List[Status],
    hashtags: List[Tag]
)
