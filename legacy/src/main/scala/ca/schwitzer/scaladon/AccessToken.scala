package ca.schwitzer.scaladon

import akka.http.javadsl.model.headers.HttpCredentials

case class AccessToken(credentials: HttpCredentials)
