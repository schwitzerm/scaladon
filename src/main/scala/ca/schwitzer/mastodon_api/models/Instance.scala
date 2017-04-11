package ca.schwitzer.mastodon_api.models

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Instance(title: String,
                    description: String,
                    email: String,
                    URI: String)

object Instance {
  implicit val reads: Reads[Instance] = (
    (__ \ "title").read[String] and
    (__ \ "description").read[String] and
    (__ \ "email").read[String] and
    (__ \ "uri").read[String]
  )(Instance.apply _)
}
