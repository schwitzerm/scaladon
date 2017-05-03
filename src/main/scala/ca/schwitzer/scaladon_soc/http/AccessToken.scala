package ca.schwitzer.scaladon_soc.http

import akka.http.javadsl.model.headers.HttpCredentials

case class AccessToken(credentials: HttpCredentials)
