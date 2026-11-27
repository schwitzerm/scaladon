package ca.schwitzer.scaladon

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.api.{EndpointRunner, Instances, Statuses}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend}

/** The client, parameterised by its effect.
  *
  * It is intentionally a plain value: all namespaces close over nothing mutable, so the client can
  * be shared, transformed, and tested. Expected failures are raised as `ApiException` inside `F`;
  * `attemptApi` recovers them as values. Only the ported namespaces appear so far; `Accounts`,
  * `Timelines`, and the rest of the legacy surface will be added one algebra at a time.
  */
final case class Mastodon[F[_]](statuses: Statuses[F], instances: Instances[F]):

  /** Translate the client to another effect without changing what it does. */
  def mapK[G[_]: MonadThrow](transform: F ~> G): Mastodon[G] =
    Mastodon(statuses.mapK(transform), instances.mapK(transform))

object Mastodon:

  def live[F[_]: MonadThrow](
      backend: HttpBackend[F],
      host: Host,
      token: BearerToken
  ): Mastodon[F] =
    val runner = EndpointRunner(backend, host, Some(token))
    Mastodon(Statuses.live(runner), Instances.live(runner))

  /** A client with no credentials: public endpoints work, authenticated ones fail with `NotAuthenticated`. */
  def anonymous[F[_]: MonadThrow](backend: HttpBackend[F], host: Host): Mastodon[F] =
    val runner = EndpointRunner.anonymous(backend, host)
    Mastodon(Statuses.live(runner), Instances.live(runner))
