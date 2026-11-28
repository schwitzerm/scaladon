package ca.schwitzer.scaladon.http

import java.nio.charset.StandardCharsets

/** One part of a `multipart/form-data` body.
  *
  * A part is either a text field (no filename) or a file field (`filename` and, normally, a
  * `contentType`). The bytes are kept raw so the transport layer never has to guess an encoding;
  * use [[MultipartPart.text]] for UTF-8 strings.
  */
final case class MultipartPart(
    name: String,
    filename: Option[String] = None,
    contentType: Option[String] = None,
    bytes: Array[Byte]
)

object MultipartPart:

  /** A UTF-8 text field, such as `description` or `focus`. */
  def text(name: String, value: String): MultipartPart =
    MultipartPart(name, bytes = value.getBytes(StandardCharsets.UTF_8))

  /** A file field, such as `file` or `thumbnail`. */
  def file(name: String, filename: String, contentType: String, bytes: Array[Byte]): MultipartPart =
    MultipartPart(name, Some(filename), Some(contentType), bytes)

/** A `multipart/form-data` body.
  *
  * The boundary is a property of the encoding, not of the value, so the interpreter picks it.
  */
final case class Multipart(parts: List[MultipartPart])
