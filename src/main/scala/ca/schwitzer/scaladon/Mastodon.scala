package ca.schwitzer.scaladon

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.api.{
  Accounts,
  Collections,
  EndpointRunner,
  Instances,
  Notifications,
  OAuth,
  Profile,
  Statuses,
  Timelines
}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend}

/** The client, parameterised by its effect.
  *
  * It is intentionally a plain value: all namespaces close over nothing mutable, so the client can
  * be shared, transformed, and tested. Expected failures are raised as `ApiException` inside `F`;
  * `attemptApi` recovers them as values. Every namespace the v12 adapter supports is present;
  * adding another means a field here plus its wiring in both constructors and `mapK`.
  */
final case class Mastodon[F[_]](
    statuses: Statuses[F],
    instances: Instances[F],
    accounts: Accounts[F],
    profile: Profile[F],
    timelines: Timelines[F],
    collections: Collections[F],
    notifications: Notifications[F],
    oauth: OAuth[F]
):

  /** Translate the client to another effect without changing what it does. */
  def mapK[G[_]: MonadThrow](transform: F ~> G): Mastodon[G] =
    Mastodon(
      statuses.mapK(transform),
      instances.mapK(transform),
      accounts.mapK(transform),
      profile.mapK(transform),
      timelines.mapK(transform),
      collections.mapK(transform),
      notifications.mapK(transform),
      oauth.mapK(transform)
    )

object Mastodon:

  def live[F[_]: MonadThrow](
      backend: HttpBackend[F],
      host: Host,
      token: BearerToken
  ): Mastodon[F] =
    fromRunner(EndpointRunner(backend, host, Some(token)))

  /** A client with no credentials: public endpoints work, authenticated ones fail with `NotAuthenticated`. */
  def anonymous[F[_]: MonadThrow](backend: HttpBackend[F], host: Host): Mastodon[F] =
    fromRunner(EndpointRunner.anonymous(backend, host))

  private def fromRunner[F[_]: MonadThrow](runner: EndpointRunner[F]): Mastodon[F] =
    Mastodon(
      Statuses.live(runner),
      Instances.live(runner),
      Accounts.live(runner),
      Profile.live(runner),
      Timelines.live(runner),
      Collections.live(runner),
      Notifications.live(runner),
      OAuth.live(runner)
    )
