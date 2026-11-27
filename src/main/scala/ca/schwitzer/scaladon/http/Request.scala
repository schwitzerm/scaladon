package ca.schwitzer.scaladon.http

import io.circe.Json

/** A transport request. `url` is absolute (scheme, host, path and query already assembled). */
final case class Request(
    method: HttpMethod,
    url: String,
    headers: Map[String, String] = Map.empty,
    body: Option[Json] = None
):
  def withHeader(name: String, value: String): Request =
    copy(headers = headers.updated(name, value))
