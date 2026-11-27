package ca.schwitzer.scaladon

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.api.{
  Accounts,
  Announcements,
  AsyncRefreshes,
  Blocks,
  Collections,
  EndpointRunner,
  Favourites,
  Filters,
  FollowRequests,
  Instances,
  Lists,
  Markers,
  Media,
  Mutes,
  Notifications,
  OAuth,
  Polls,
  Profile,
  Push,
  Reports,
  ScheduledStatuses,
  Search,
  Statuses,
  Suggestions,
  Timelines,
  Trends
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
    oauth: OAuth[F],
    media: Media[F],
    scheduledStatuses: ScheduledStatuses[F],
    asyncRefreshes: AsyncRefreshes[F],
    polls: Polls[F],
    search: Search[F],
    favourites: Favourites[F],
    blocks: Blocks[F],
    mutes: Mutes[F],
    followRequests: FollowRequests[F],
    reports: Reports[F],
    lists: Lists[F],
    filters: Filters[F],
    markers: Markers[F],
    announcements: Announcements[F],
    trends: Trends[F],
    suggestions: Suggestions[F],
    push: Push[F]
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
      oauth.mapK(transform),
      media.mapK(transform),
      scheduledStatuses.mapK(transform),
      asyncRefreshes.mapK(transform),
      polls.mapK(transform),
      search.mapK(transform),
      favourites.mapK(transform),
      blocks.mapK(transform),
      mutes.mapK(transform),
      followRequests.mapK(transform),
      reports.mapK(transform),
      lists.mapK(transform),
      filters.mapK(transform),
      markers.mapK(transform),
      announcements.mapK(transform),
      trends.mapK(transform),
      suggestions.mapK(transform),
      push.mapK(transform)
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
      OAuth.live(runner),
      Media.live(runner),
      ScheduledStatuses.live(runner),
      AsyncRefreshes.live(runner),
      Polls.live(runner),
      Search.live(runner),
      Favourites.live(runner),
      Blocks.live(runner),
      Mutes.live(runner),
      FollowRequests.live(runner),
      Reports.live(runner),
      Lists.live(runner),
      Filters.live(runner),
      Markers.live(runner),
      Announcements.live(runner),
      Trends.live(runner),
      Suggestions.live(runner),
      Push.live(runner)
    )
