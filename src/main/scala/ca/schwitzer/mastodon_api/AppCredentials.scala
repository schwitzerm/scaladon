package ca.schwitzer.mastodon_api

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class AppCredentials(id: Int, clientId: String, clientSecret: String)

object AppCredentials {
  implicit val reads: Reads[AppCredentials] = (
    (JsPath \ "id").read[Int] and
    (JsPath \ "client_id").read[String] and
    (JsPath \ "client_secret").read[String]
  )(AppCredentials.apply _)

  implicit val writes: Writes[AppCredentials] = (
    (JsPath \ "id").write[Int] and
    (JsPath \ "client_id").write[String] and
    (JsPath \ "client_secret").write[String]
  )(unlift(AppCredentials.unapply))
}
