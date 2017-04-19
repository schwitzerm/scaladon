package ca.schwitzer.scaladon.models.mastodon

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Tag(name: String, URL: String)

object Tag {
  implicit val reads: Reads[Tag] = (
    (__ \ "name").read[String] and
    (__ \ "url").read[String]
  )(Tag.apply _)
}
