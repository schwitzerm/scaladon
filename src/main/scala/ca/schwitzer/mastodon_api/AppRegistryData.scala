package ca.schwitzer.mastodon_api

import play.api.libs.json._

case class AppRegistryData(clientName: String, scopes: Seq[String], redirectURIs: Seq[String])

object AppRegistryData {
  implicit val writes: Writes[AppRegistryData] = (data: AppRegistryData) => Json.obj(
    "client_name" -> data.clientName,
    "scopes" -> data.scopes.mkString(" "),
    "redirect_uris" -> data.redirectURIs.mkString(" ")
  )
}
