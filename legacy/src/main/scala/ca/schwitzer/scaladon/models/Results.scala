package ca.schwitzer.scaladon.models

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Results(accounts: Seq[Account],
                   statuses: Seq[Status],
                   hashtags: Seq[String])

object Results {
  implicit val reads: Reads[Results] = (
    (__ \ "accounts").read[Seq[Account]] and
    (__ \ "statuses").read[Seq[Status]] and
    (__ \ "hashtags").read[Seq[String]]
  )(Results.apply _)
}
