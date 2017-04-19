package ca.schwitzer.scaladon.models.mastodon

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Card(title: String,
                description: String,
                URL: String,
                image: String)

object Card {
  implicit val read: Reads[Card] = (
    (__ \ "title").read[String] and
    (__ \ "description").read[String] and
    (__ \ "url").read[String] and
    (__ \ "image").read[String]
  )(Card.apply _)
}
