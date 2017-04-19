package ca.schwitzer.scaladon.models.mastodon

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Mention(accountId: Int,
                   username: String,
                   acct: String,
                   URL: String)

object Mention {
  implicit val reads: Reads[Mention] = (
    (__ \ "id").read[Int] and
    (__ \ "username").read[String] and
    (__ \ "acct").read[String] and
    (__ \ "url").read[String]
  )(Mention.apply _)
}
