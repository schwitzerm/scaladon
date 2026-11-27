package ca.schwitzer.scaladon.http4s

import cats.effect.{Async, Resource}
import java.util.Locale
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
import ca.schwitzer.scaladon.http.{HttpBackend, HttpMethod, Request, Response}

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
      .map { uri =>
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
            base
              .withEntity(json.noSpaces)
              .withContentType(`Content-Type`(MediaType.application.json))
          case None =>
            request.form match
              case Some(fields) => base.withEntity(UrlForm(fields*))
              case None         => base
      }

  private def readBody[F[_]: Async](response: Http4sResponse[F]): F[Response] =
    response.bodyText.compile.string.map { body =>
      val headers = response.headers.headers
        .map(header => header.name.toString.toLowerCase(Locale.ROOT) -> header.value)
        .toMap
      Response(HttpStatus(response.status.code), body, headers)
    }

  private def toMethod(method: HttpMethod): Method = method match
    case HttpMethod.GET    => Method.GET
    case HttpMethod.POST   => Method.POST
    case HttpMethod.PUT    => Method.PUT
    case HttpMethod.PATCH  => Method.PATCH
    case HttpMethod.DELETE => Method.DELETE
