package ca.schwitzer.scaladon.models

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Attachment(id: Int,
                      attachmentType: AttachmentType,
                      URL: String,
                      remoteURL: String,
                      previewURL: String,
                      textURL: Option[String])

object Attachment {
  implicit val reads: Reads[Attachment] = (
    (__ \ "id").read[Int] and
    (__ \ "type").read[AttachmentType] and
    (__ \ "url").read[String] and
    (__ \ "remote_url").read[String] and
    (__ \ "preview_url").read[String] and
    (__ \ "text_url").readNullable[String]
  )(Attachment.apply _)
}

sealed trait AttachmentType { self =>
  override def toString: String = self match {
    case AttachmentTypes.Image => "image"
    case AttachmentTypes.Video => "video"
    case AttachmentTypes.Gifv => "gifv"
  }
}

object AttachmentType {
  implicit val reads: Reads[AttachmentType] = __.read[String].map {
    case "image" => AttachmentTypes.Image
    case "video" => AttachmentTypes.Video
    case "gifv" => AttachmentTypes.Gifv
  }
}

object AttachmentTypes {
  final case object Image extends AttachmentType
  final case object Video extends AttachmentType
  final case object Gifv extends AttachmentType
}
