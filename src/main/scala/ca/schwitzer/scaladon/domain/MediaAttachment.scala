package ca.schwitzer.scaladon.domain

/** The attachment kinds the media API reports. `unknown` is a documented server value. */
enum MediaType(val wire: String):
  case Image   extends MediaType("image")
  case Gifv    extends MediaType("gifv")
  case Video   extends MediaType("video")
  case Audio   extends MediaType("audio")
  case Unknown extends MediaType("unknown")

object MediaType:
  def fromWire(value: String): Option[MediaType] = value match
    case "image"   => Some(Image)
    case "gifv"    => Some(Gifv)
    case "video"   => Some(Video)
    case "audio"   => Some(Audio)
    case "unknown" => Some(Unknown)
    case _         => None

/** A media attachment, before or after it is attached to a status.
  *
  * `url` is `None` while an asynchronous upload is still processing (HTTP 202); `previewUrl` is
  * usually available earlier. Remote and text URLs are optional on every payload.
  */
final case class MediaAttachment(
    id: Id,
    kind: MediaType,
    url: Option[String],
    previewUrl: Option[String],
    remoteUrl: Option[String],
    textUrl: Option[String],
    description: Option[String],
    blurhash: Option[String]
)
