package ca.schwitzer.scaladon.domain

/** A link that is trending on the server (`GET /api/v1/trends/links`).
  *
  * The wire entity (`Trends::Link`) is a [[PreviewCard]] plus a usage `history`: the same fields,
  * flattened rather than nested under a `card` key, and no `missing_attribution`. `cardType` keeps
  * [[PreviewCardType]] because the wire values are identical. `history` defaults to `Nil` because
  * the server may omit it for links it has just started tracking.
  */
final case class TrendLink(
    url: String,
    title: String,
    description: String,
    cardType: PreviewCardType,
    authorName: String,
    authorUrl: String,
    providerName: String,
    providerUrl: String,
    html: String,
    width: Int,
    height: Int,
    image: Option[String],
    embedUrl: String,
    blurhash: Option[String],
    history: List[TagHistory]
)
