package ca.schwitzer.scaladon.domain

import cats.Show

/** An HTTP status code as a value, so responses can be interpreted without importing a client library. */
final case class HttpStatus(code: Int):
  def isSuccess: Boolean = code >= 200 && code < 300

object HttpStatus:
  val Ok: HttpStatus          = HttpStatus(200)
  val Created: HttpStatus     = HttpStatus(201)
  val Unauthorized: HttpStatus = HttpStatus(401)
  val NotFound: HttpStatus    = HttpStatus(404)

  given Show[HttpStatus] = Show.show(_.code.toString)
