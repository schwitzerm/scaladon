package ca.schwitzer.scaladon.models

import akka.http.javadsl.model.headers.HttpCredentials

case class AccessToken(credentials: HttpCredentials)
