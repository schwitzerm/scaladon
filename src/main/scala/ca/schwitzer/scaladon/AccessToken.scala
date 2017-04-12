package ca.schwitzer.scaladon

import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.headers.Cookie
import play.api.libs.json._

case class AccessToken(credentials: HttpCredentials)
