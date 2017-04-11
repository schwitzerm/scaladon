package ca.schwitzer.scaladon.models

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Application(name: String,
                       website: String)

object Application {
  implicit val reads: Reads[Application] = (
    (__ \ "name").read[String] and
    (__ \ "website").read[String]
  )(Application.apply _)
}
