package ca.schwitzer.scaladon.api

import io.circe.{Decoder, Json}

import ca.schwitzer.scaladon.domain.ApiError
import ca.schwitzer.scaladon.http.{HttpMethod, Multipart, MultipartPart}

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
    decode: Json => Either[ApiError, A],
    form: Option[List[(String, String)]] = None,
    multipart: Option[Multipart] = None
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

  /** `PATCH` with a JSON body, e.g. profile updates. */
  def patch[A: Decoder](
      path: String,
      body: Json,
      authenticated: Boolean = true
  ): Endpoint[A] =
    Endpoint(HttpMethod.PATCH, path, Nil, Some(body), authenticated, decodeWith[A])

  /** A `POST` with an `application/x-www-form-urlencoded` body, e.g. the OAuth token endpoint. */
  def postForm[A: Decoder](
      path: String,
      fields: List[(String, String)],
      authenticated: Boolean = true
  ): Endpoint[A] =
    Endpoint(
      HttpMethod.POST,
      path,
      Nil,
      None,
      authenticated,
      decodeWith[A],
      form = Some(fields)
    )

  /** A `PATCH` with a form body, for endpoints documented as form data. */
  def patchForm[A: Decoder](
      path: String,
      fields: List[(String, String)],
      authenticated: Boolean = true
  ): Endpoint[A] =
    Endpoint(
      HttpMethod.PATCH,
      path,
      Nil,
      None,
      authenticated,
      decodeWith[A],
      form = Some(fields)
    )

  /** Endpoints that answer with an empty object (`{}`), such as notifications/clear. */
  def unit(
      method: HttpMethod,
      path: String,
      body: Option[Json] = None,
      authenticated: Boolean = true
  ): Endpoint[Unit] =
    Endpoint(method, path, Nil, body, authenticated, _ => Right(()))

  /** A unit-returning endpoint with a form body, e.g. OAuth revoke. */
  def unitForm(
      method: HttpMethod,
      path: String,
      fields: List[(String, String)],
      authenticated: Boolean = true
  ): Endpoint[Unit] =
    Endpoint(method, path, Nil, None, authenticated, _ => Right(()), form = Some(fields))

  /** A request with a `multipart/form-data` body, e.g. media uploads and thumbnail updates. */
  def multipart[A: Decoder](
      method: HttpMethod,
      path: String,
      parts: List[MultipartPart],
      authenticated: Boolean = true
  ): Endpoint[A] =
    Endpoint(
      method,
      path,
      Nil,
      None,
      authenticated,
      decodeWith[A],
      multipart = Some(Multipart(parts))
    )

  private def decodeWith[A](using decoder: Decoder[A]): Json => Either[ApiError, A] =
    json => decoder.decodeJson(json).left.map(failure => ApiError.Decode(failure.message))
