package ca.schwitzer.scaladon.http

import io.circe.Json
import io.circe.parser

import java.util.Locale

import ca.schwitzer.scaladon.domain.{ApiError, HttpStatus}

/** A transport response, still raw: decoding is the job of the API layer.
  *
  * Headers are kept, not dropped: rate limiting (`Retry-After`) and future error metadata are only
  * reachable through them. Keys are stored lower-cased so lookups are case-insensitive.
  */
final case class Response(
    status: HttpStatus,
    body: String,
    headers: Map[String, String] = Map.empty
):
  def header(name: String): Option[String] =
    val wanted = name.toLowerCase(Locale.ROOT)
    headers.collectFirst { case (key, value) if key.toLowerCase(Locale.ROOT) == wanted => value }

  def json: Either[ApiError, Json] =
    parser.parse(body).left.map(failure => ApiError.Decode(failure.message))
