package ca.schwitzer.scaladon.models

import play.api.libs.json._

case class MastodonError(error: String)

object MastodonError {
  implicit val reads: Reads[MastodonError] = (__ \ "error").read[String].map(MastodonError.apply)
}
