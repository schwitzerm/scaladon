package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, Request, Response}

/** Interprets [[Endpoint]] values against an [[HttpBackend]].
  *
  * Compiles the description into a [[Request]], attaches the bearer token when the endpoint needs
  * one, and raises an [[ApiException]] carrying a structured [[ApiError]] for every expected
  * failure. `F` needs only `MonadThrow`: any effect with an error channel works, and `mapK`
  * translates the runner between effects.
  */
final case class EndpointRunner[F[_]: MonadThrow](
    backend: HttpBackend[F],
    host: Host,
    token: Option[BearerToken]
):

  private val effect: MonadThrow[F] = MonadThrow[F]

  def run[A](endpoint: Endpoint[A]): F[A] =
    effect.map(runWithResponse(endpoint))(_._1)

  /** Like [[run]], but also returns the raw response.
    *
    * Some endpoints carry meaning in headers rather than the body: status context returns
    * `Mastodon-Async-Refresh` while replies are fetched in the background. Rate limiting is
    * already modelled in [[ApiError]]; this is for the rest.
    */
  def runWithResponse[A](endpoint: Endpoint[A]): F[(A, Response)] =
    if endpoint.authenticated && token.isEmpty then
      effect.raiseError(ApiException(ApiError.NotAuthenticated(endpoint.path)))
    else
      // Wrap transport failures, but let cancellation pass through: cancelling is not an API error.
      val response = effect.adaptError(backend.run(build(endpoint))) {
        case cause if !cause.isInstanceOf[CancellationException] =>
          ApiException(ApiError.Transport(cause))
      }
      effect.flatMap(response) { value =>
        interpret(endpoint, value) match
          case Left(error) => effect.raiseError(ApiException(error))
          case Right(result) => effect.pure((result, value))
      }

  def mapK[G[_]: MonadThrow](transform: F ~> G): EndpointRunner[G] =
    EndpointRunner(backend.mapK(transform), host, token)

  private def build[A](endpoint: Endpoint[A]): Request =
    val url = s"${host.baseUrl}${endpoint.path}${queryString(endpoint.query)}"
    val base = Request(endpoint.method, url, body = endpoint.body, form = endpoint.form)
    if endpoint.authenticated then
      token.fold(base)(t => base.withHeader("Authorization", s"Bearer ${t.value}"))
    else base

  private def queryString(params: List[(String, String)]): String =
    params match
      case Nil => ""
      case _ =>
        val encoded = params.map { case (key, value) =>
          s"${encode(key)}=${encode(value)}"
        }
        "?" + encoded.mkString("&")

  private def encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def interpret[A](endpoint: Endpoint[A], response: Response): Either[ApiError, A] =
    if response.status.isSuccess then response.json.flatMap(endpoint.decode)
    else Left(httpError(response))

  private def httpError(response: Response): ApiError =
    val message = response.json.toOption.flatMap(_.hcursor.get[String]("error").toOption)
    if response.status.code == 429 then ApiError.RateLimited(retryAfter(response), message)
    else ApiError.Http(response.status, message, response.body)

  private def retryAfter(response: Response): Option[FiniteDuration] =
    response.header("Retry-After").flatMap(_.trim.toLongOption).map(_.seconds)

object EndpointRunner:
  /** An unauthenticated client: public endpoints work, authenticated ones fail with `NotAuthenticated`. */
  def anonymous[F[_]: MonadThrow](backend: HttpBackend[F], host: Host): EndpointRunner[F] =
    EndpointRunner(backend, host, None)
