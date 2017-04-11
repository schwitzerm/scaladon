package ca.schwitzer.mastodon_api.models

import play.api.libs.json._

case class Error(error: String)

object Error {
  implicit val reads: Reads[Error] = (__ \ "error").read[String].map(Error.apply)
}
