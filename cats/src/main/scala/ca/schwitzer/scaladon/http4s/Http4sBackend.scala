package ca.schwitzer.scaladon.http4s

import cats.effect.{Async, Resource}
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.{Locale, UUID}
import fs2.io.net.Network
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import org.http4s.{
  Header,
  Headers,
  MediaType,
  Method,
  Request as Http4sRequest,
  Response as Http4sResponse,
  Uri,
  UrlForm
}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import ca.schwitzer.scaladon.domain.HttpStatus
import ca.schwitzer.scaladon.http.{HttpBackend, HttpMethod, Multipart, Request, Response}

/** The http4s interpreter of [[HttpBackend]].
  *
  * http4s is the client library of the Cats Effect ecosystem (cats-effect + fs2), and ember is its
  * pure, effect-native backend. This is the only package in the project that knows a concrete HTTP
  * library; everything above `HttpBackend` stays transport-agnostic.
  *
  * `Async` is required here — not on the algebras — because ember needs it for connection
  * management and response streaming, while the pure layers only map (`Applicative`). Keeping the
  * algebras weak is what allows `mapK` to translate them to effects without an `Async` instance
  * (notably `Future`, which has none) — see `translation.FutureTranslation`.
  */
object Http4sBackend:

  /** Wrap any http4s `Client` (ember, blaze, a test client) as an [[HttpBackend]]. */
  def fromClient[F[_]: Async](client: Client[F]): HttpBackend[F] =
    HttpBackend.of[F] { request =>
      for
        http4sRequest <- toHttp4sRequest[F](request).liftTo[F]
        response      <- client.run(http4sRequest).use(readBody[F])
      yield response
    }

  /** An ember client, owned as a `Resource` so the connection pool is released deterministically. */
  def resource[F[_]: Async: Network]: Resource[F, HttpBackend[F]] =
    EmberClientBuilder.default[F].build.map(fromClient[F])

  private def toHttp4sRequest[F[_]](request: Request): Either[Throwable, Http4sRequest[F]] =
    Uri
      .fromString(request.url)
      .leftMap(failure =>
        new IllegalArgumentException(s"Invalid URL '${request.url}': ${failure.message}")
      )
      .flatMap { uri =>
        val headers = request.headers.foldLeft(Headers.empty) { case (acc, (name, value)) =>
          acc.put(Header.Raw(CIString(name), value))
        }
        val base = Http4sRequest[F](
          method = toMethod(request.method),
          uri = uri,
          headers = headers
        )
        request.body match
          case Some(json) =>
            Right(
              base
                .withEntity(json.noSpaces)
                .withContentType(`Content-Type`(MediaType.application.json))
            )
          case None =>
            request.multipart match
              case Some(multipart) =>
                encodeMultipart(multipart).map { case (boundary, bytes) =>
                  base
                    .withEntity(bytes)
                    .withContentType(`Content-Type`(multipartMediaType(boundary)))
                }
              case None =>
                request.form match
                  case Some(fields) => Right(base.withEntity(UrlForm(fields*)))
                  case None         => Right(base)
      }

  private def readBody[F[_]: Async](response: Http4sResponse[F]): F[Response] =
    response.bodyText.compile.string.map { body =>
      val headers = response.headers.headers
        .map(header => header.name.toString.toLowerCase(Locale.ROOT) -> header.value)
        .toMap
      Response(HttpStatus(response.status.code), body, headers)
    }

  /** A fresh boundary for every encoding: a fixed value lets crafted upload bytes collide with the
    * framing, so the boundary is an encoding property, not part of the `Multipart` value.
    */
  private def freshBoundary: String = s"----scaladon-${UUID.randomUUID()}"

  private def multipartMediaType(boundary: String): MediaType =
    MediaType.multipartType("form-data", Some(boundary))

  private def encodeMultipart(
      multipart: Multipart
  ): Either[IllegalArgumentException, (String, Array[Byte])] =
    multipart.parts.iterator.flatMap(_.contentType).find(hasForbiddenHeaderCharacter) match
      case Some(value) =>
        Left(
          new IllegalArgumentException(
            s"Invalid multipart part content type '$value': CR, LF and NUL are not allowed"
          )
        )
      case None =>
        val boundary = freshBoundary
        val out = new ByteArrayOutputStream()
        def write(value: String): Unit = out.write(value.getBytes(StandardCharsets.UTF_8))
        multipart.parts.foreach { part =>
          write(s"--$boundary\r\n")
          write(s"Content-Disposition: form-data; name=\"${quoted(part.name)}\"")
          part.filename.foreach(name => write(s"; filename=\"${quoted(name)}\""))
          write("\r\n")
          part.contentType.foreach(value => write(s"Content-Type: $value\r\n"))
          write("\r\n")
          out.write(part.bytes)
          write("\r\n")
        }
        write(s"--$boundary--\r\n")
        Right((boundary, out.toByteArray))

  private def hasForbiddenHeaderCharacter(value: String): Boolean =
    value.exists(character => character == '\r' || character == '\n' || character == '\u0000')

  /** Keep a header parameter on one line and inside its quoted string. */
  private def quoted(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")

  private def toMethod(method: HttpMethod): Method = method match
    case HttpMethod.GET    => Method.GET
    case HttpMethod.POST   => Method.POST
    case HttpMethod.PUT    => Method.PUT
    case HttpMethod.PATCH  => Method.PATCH
    case HttpMethod.DELETE => Method.DELETE
