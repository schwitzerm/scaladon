package ca.schwitzer.mastodon_api.models

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Report(id: Int,
                  actionTaken: String)

object Report {
  implicit val reads: Reads[Report] = (
    (__ \ "id").read[Int] and
    (__ \ "action_taken").read[String]
  )(Report.apply _)
}
