package ca.schwitzer.scaladon.api

import io.circe.{Decoder, Json}

import ca.schwitzer.scaladon.domain.ApiError
import ca.schwitzer.scaladon.http.HttpMethod

/** An API call described as data: method, path, query, body, whether it needs a token, and how to
  * decode the result. Nothing here performs effects, so endpoints can be inspected, printed,
  * composed, or run against a stub backend.
  *
  * This is the replacement for the legacy "one method builds a request and fires it" style: the
  * request description is an initial algebra, and `EndpointRunner` is one of its interpreters.
  */
final case class Endpoint[A](
    method: HttpMethod,
    path: String,
    query: List[(String, String)],
    body: Option[Json],
    authenticated: Boolean,
    decode: Json => Either[ApiError, A]
):
  def withQuery(params: (String, String)*): Endpoint[A] =
    copy(query = query ++ params)

object Endpoint:

  def get[A: Decoder](
      path: String,
      query: List[(String, String)] = Nil,
      authenticated: Boolean = true
  ): Endpoint[A] =
    Endpoint(HttpMethod.GET, path, query, None, authenticated, decodeWith[A])

  def post[A: Decoder](
      path: String,
      body: Json,
      authenticated: Boolean = true
  ): Endpoint[A] =
    Endpoint(HttpMethod.POST, path, Nil, Some(body), authenticated, decodeWith[A])

  /** Endpoints that answer with an empty object (`{}`), such as notifications/clear. */
  def unit(
      method: HttpMethod,
      path: String,
      body: Option[Json] = None,
      authenticated: Boolean = true
  ): Endpoint[Unit] =
    Endpoint(method, path, Nil, body, authenticated, _ => Right(()))

  private def decodeWith[A](using decoder: Decoder[A]): Json => Either[ApiError, A] =
    json => decoder.decodeJson(json).left.map(failure => ApiError.Decode(failure.message))
