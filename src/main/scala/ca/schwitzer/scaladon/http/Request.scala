package ca.schwitzer.scaladon.http

import java.util.Locale

import io.circe.Json

/** A transport request. `url` is absolute (scheme, host, path and query already assembled).
  *
  * [[toString]] is the sanctioned human/log rendering and redacts credentials: bearer headers and
  * credential-bearing form fields are replaced with `**redacted**`, and the body/multipart payload
  * is reduced to a presence marker. The header map still stores the raw `Authorization` value
  * because it is the wire contract; protocol-seams replaces that storage with a typed token.
  */
final case class Request(
    method: HttpMethod,
    url: String,
    headers: Map[String, String] = Map.empty,
    body: Option[Json] = None,
    form: Option[List[(String, String)]] = None,
    multipart: Option[Multipart] = None
):
  def withHeader(name: String, value: String): Request =
    copy(headers = headers.updated(name, value))

  override def toString: String =
    val renderedHeaders =
      headers.toList
        .sortBy { case (name, _) => name.toLowerCase(Locale.ROOT) }
        .map { case (name, value) =>
          val shown = if Request.isCredentialHeader(name) then Request.Redacted else value
          s"$name=$shown"
        }
        .mkString("headers=[", ", ", "]")
    val renderedForm = form match
      case None => "form=absent"
      case Some(fields) =>
        fields
          .map { case (name, value) =>
            val shown = if Request.isCredentialFormField(name) then Request.Redacted else value
            s"$name=$shown"
          }
          .mkString("form=[", ", ", "]")
    val renderedBody = if body.isDefined then "body=present" else "body=absent"
    val renderedMultipart = if multipart.isDefined then "multipart=present" else "multipart=absent"
    s"Request(${method.name}, $url, $renderedHeaders, $renderedForm, $renderedBody, $renderedMultipart)"

object Request:

  private val Redacted = "**redacted**"

  private val credentialHeaders = Set("authorization", "proxy-authorization")

  /** Credential-bearing form names, matched against the last bracketed segment so nested keys
    * such as `subscription[keys][auth]` are covered as well as top-level fields.
    */
  private val credentialFormFields =
    Set(
      "client_secret",
      "code",
      "refresh_token",
      "access_token",
      "password",
      "token",
      "code_verifier",
      "auth"
    )

  private def isCredentialHeader(name: String): Boolean =
    credentialHeaders.contains(name.toLowerCase(Locale.ROOT))

  private def isCredentialFormField(name: String): Boolean =
    credentialFormFields.contains(lastFormSegment(name))

  private def lastFormSegment(name: String): String =
    val normalized = name.toLowerCase(Locale.ROOT)
    if normalized.endsWith("]") then
      val opening = normalized.lastIndexOf('[')
      if opening >= 0 then normalized.substring(opening + 1, normalized.length - 1)
      else normalized
    else normalized
