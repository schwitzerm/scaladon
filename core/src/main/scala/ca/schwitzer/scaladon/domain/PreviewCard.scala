package ca.schwitzer.scaladon.domain

/** The kind of embedded preview a link resolves to. */
enum PreviewCardType(val wire: String):
  case Link  extends PreviewCardType("link")
  case Photo extends PreviewCardType("photo")
  case Video extends PreviewCardType("video")
  case Rich  extends PreviewCardType("rich")

object PreviewCardType:
  def all: List[PreviewCardType] = values.toList

  def fromWire(value: String): Option[PreviewCardType] = all.find(_.wire == value)


/** A rich preview card generated from a link in a status (`Status.card`).
  *
  * `authorName`/`authorUrl` are the deprecated 1.3 fields kept for older servers; `missingAttribution`
  * is the 4.6 flag a client uses to prompt the user to add the domain to their attribution domains.
  */
final case class PreviewCard(
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
    missingAttribution: Boolean
)
