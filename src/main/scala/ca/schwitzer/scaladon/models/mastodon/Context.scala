package ca.schwitzer.scaladon.models.mastodon

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Context(ancestors: Seq[Status], descendants: Seq[Status])

object Context {
  implicit val reads: Reads[Context] = (
    (__ \ "ancestors").read[Seq[Status]] and
    (__ \ "descendants").read[Seq[Status]]
  )(Context.apply _)
}
