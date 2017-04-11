package ca.schwitzer.scaladon

import akka.http.javadsl.model.headers.HttpCredentials

case class AccessToken(value: String) {
  def asCredentials: HttpCredentials = HttpCredentials.createOAuth2BearerToken(value)
}
