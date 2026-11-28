package ca.schwitzer.scaladon.domain

/** One day of hashtag usage statistics, as embedded in a [[Tag]]. */
final case class TagHistory(day: String, uses: Int, accounts: Int)

/** A hashtag entity, shared by search results, trends and featured tags. */
final case class Tag(
    name: String,
    url: String,
    history: List[TagHistory] = Nil,
    following: Option[Boolean] = None
)
