package ca.schwitzer.scaladon.http

import cats.Show

/** An OAuth bearer token, kept opaque so it cannot be logged or concatenated by accident.
  * It has no dependency on a client library's credentials type.
  */
opaque type BearerToken = String

object BearerToken:
  def apply(value: String): BearerToken = value

  extension (token: BearerToken) def value: String = token

  given Show[BearerToken] = Show.show(_ => "BearerToken(**redacted**)")
