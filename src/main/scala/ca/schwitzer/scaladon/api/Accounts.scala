package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import scala.concurrent.duration.FiniteDuration

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Account, CredentialAccount, Id, Relationship, Status}

/** The "Accounts" section of the Mastodon API, as an algebra of values.
  *
  * `fetch`, `statuses`, `followers`, `following` and the follow/block/mute actions are public or
  * user-token endpoints exactly as documented; the runnable endpoints therefore mark the public
  * ones `authenticated = false` so no token leaks onto a request that does not need it.
  *
  * `verifyCredentials` exposes the Mastodon 5.0 `allow_nonfunctional` query parameter: it is only
  * sent when `true`, so the default request is byte-for-byte the pre-5.0 call.
  */
trait Accounts[F[_]]:
  def verifyCredentials(allowNonfunctional: Boolean = false): F[CredentialAccount]

  def fetch(id: Id): F[Account]

  def statuses(
      id: Id,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None,
      onlyMedia: Option[Boolean] = None,
      excludeReplies: Option[Boolean] = None,
      excludeReblogs: Option[Boolean] = None,
      pinned: Option[Boolean] = None,
      tagged: Option[String] = None
  ): F[List[Status]]

  def followers(
      id: Id,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Account]]

  def following(
      id: Id,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Account]]

  def follow(
      id: Id,
      reblogs: Option[Boolean] = None,
      notify: Option[Boolean] = None,
      languages: List[String] = Nil
  ): F[Relationship]

  def unfollow(id: Id): F[Relationship]

  def block(id: Id): F[Relationship]

  def unblock(id: Id): F[Relationship]

  def mute(
      id: Id,
      notifications: Option[Boolean] = None,
      duration: Option[FiniteDuration] = None
  ): F[Relationship]

  def unmute(id: Id): F[Relationship]

  def relationships(ids: List[Id]): F[List[Relationship]]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Accounts[G]

object Accounts:

  def live[F[_]](runner: EndpointRunner[F]): Accounts[F] =
    new LiveAccounts[F](runner)

  /** `GET /api/v1/accounts/verify_credentials` — the 5.0 `allow_nonfunctional` flag is opt-in. */
  def verifyCredentialsEndpoint(allowNonfunctional: Boolean): Endpoint[CredentialAccount] =
    val endpoint = Endpoint.get[CredentialAccount]("/api/v1/accounts/verify_credentials")
    if allowNonfunctional then endpoint.withQuery("allow_nonfunctional" -> "true") else endpoint

  /** `GET /api/v1/accounts/:id` — public. */
  def fetchEndpoint(id: Id): Endpoint[Account] =
    Endpoint.get[Account](s"/api/v1/accounts/${id.value}", authenticated = false)

  /** `GET /api/v1/accounts/:id/statuses` — public for public statuses. */
  def statusesEndpoint(
      id: Id,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None,
      onlyMedia: Option[Boolean] = None,
      excludeReplies: Option[Boolean] = None,
      excludeReblogs: Option[Boolean] = None,
      pinned: Option[Boolean] = None,
      tagged: Option[String] = None
  ): Endpoint[List[Status]] =
    Endpoint.get[List[Status]](
      s"/api/v1/accounts/${id.value}/statuses",
      query = List(
        param("max_id", maxId.map(_.value)),
        param("since_id", sinceId.map(_.value)),
        param("min_id", minId.map(_.value)),
        param("limit", limit),
        param("only_media", onlyMedia),
        param("exclude_replies", excludeReplies),
        param("exclude_reblogs", excludeReblogs),
        param("pinned", pinned),
        param("tagged", tagged)
      ).flatten,
      authenticated = false
    )

  /** `GET /api/v1/accounts/:id/followers` — public. */
  def followersEndpoint(
      id: Id,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): Endpoint[List[Account]] =
    Endpoint.get[List[Account]](
      s"/api/v1/accounts/${id.value}/followers",
      query = pageQuery(maxId, sinceId, minId, limit),
      authenticated = false
    )

  /** `GET /api/v1/accounts/:id/following` — public. */
  def followingEndpoint(
      id: Id,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): Endpoint[List[Account]] =
    Endpoint.get[List[Account]](
      s"/api/v1/accounts/${id.value}/following",
      query = pageQuery(maxId, sinceId, minId, limit),
      authenticated = false
    )

  /** `POST /api/v1/accounts/:id/follow` — optional form fields, none emitted as `null`. */
  def followEndpoint(
      id: Id,
      reblogs: Option[Boolean] = None,
      notify: Option[Boolean] = None,
      languages: List[String] = Nil
  ): Endpoint[Relationship] =
    val fields =
      param("reblogs", reblogs) ++
        param("notify", notify) ++
        languages.map("languages[]" -> _)
    Endpoint.postForm[Relationship](s"/api/v1/accounts/${id.value}/follow", fields)

  def unfollowEndpoint(id: Id): Endpoint[Relationship] =
    Endpoint.postForm[Relationship](s"/api/v1/accounts/${id.value}/unfollow", Nil)

  def blockEndpoint(id: Id): Endpoint[Relationship] =
    Endpoint.postForm[Relationship](s"/api/v1/accounts/${id.value}/block", Nil)

  def unblockEndpoint(id: Id): Endpoint[Relationship] =
    Endpoint.postForm[Relationship](s"/api/v1/accounts/${id.value}/unblock", Nil)

  /** `POST /api/v1/accounts/:id/mute` — `duration` is seconds; absent means indefinite. */
  def muteEndpoint(
      id: Id,
      notifications: Option[Boolean] = None,
      duration: Option[FiniteDuration] = None
  ): Endpoint[Relationship] =
    val fields =
      param("notifications", notifications) ++
        param("duration", duration.map(_.toSeconds.toString))
    Endpoint.postForm[Relationship](s"/api/v1/accounts/${id.value}/mute", fields)

  def unmuteEndpoint(id: Id): Endpoint[Relationship] =
    Endpoint.postForm[Relationship](s"/api/v1/accounts/${id.value}/unmute", Nil)

  /** `GET /api/v1/accounts/relationships` — `id[]` is repeated once per requested account. */
  def relationshipsEndpoint(ids: List[Id]): Endpoint[List[Relationship]] =
    Endpoint
      .get[List[Relationship]]("/api/v1/accounts/relationships")
      .withQuery(ids.map(id => "id[]" -> id.value)*)

  private def pageQuery(
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): List[(String, String)] =
    param("max_id", maxId.map(_.value)) ++
      param("since_id", sinceId.map(_.value)) ++
      param("min_id", minId.map(_.value)) ++
      param("limit", limit)

  private[api] def param[A](name: String, value: Option[A]): List[(String, String)] =
    value.toList.map(rendered => name -> rendered.toString)

private final class LiveAccounts[F[_]](runner: EndpointRunner[F]) extends Accounts[F]:

  def verifyCredentials(allowNonfunctional: Boolean): F[CredentialAccount] =
    runner.run(Accounts.verifyCredentialsEndpoint(allowNonfunctional))

  def fetch(id: Id): F[Account] =
    runner.run(Accounts.fetchEndpoint(id))

  def statuses(
      id: Id,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int],
      onlyMedia: Option[Boolean],
      excludeReplies: Option[Boolean],
      excludeReblogs: Option[Boolean],
      pinned: Option[Boolean],
      tagged: Option[String]
  ): F[List[Status]] =
    runner.run(
      Accounts.statusesEndpoint(
        id,
        maxId,
        sinceId,
        minId,
        limit,
        onlyMedia,
        excludeReplies,
        excludeReblogs,
        pinned,
        tagged
      )
    )

  def followers(
      id: Id,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[Account]] =
    runner.run(Accounts.followersEndpoint(id, maxId, sinceId, minId, limit))

  def following(
      id: Id,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[Account]] =
    runner.run(Accounts.followingEndpoint(id, maxId, sinceId, minId, limit))

  def follow(
      id: Id,
      reblogs: Option[Boolean],
      notify: Option[Boolean],
      languages: List[String]
  ): F[Relationship] =
    runner.run(Accounts.followEndpoint(id, reblogs, notify, languages))

  def unfollow(id: Id): F[Relationship] =
    runner.run(Accounts.unfollowEndpoint(id))

  def block(id: Id): F[Relationship] =
    runner.run(Accounts.blockEndpoint(id))

  def unblock(id: Id): F[Relationship] =
    runner.run(Accounts.unblockEndpoint(id))

  def mute(
      id: Id,
      notifications: Option[Boolean],
      duration: Option[FiniteDuration]
  ): F[Relationship] =
    runner.run(Accounts.muteEndpoint(id, notifications, duration))

  def unmute(id: Id): F[Relationship] =
    runner.run(Accounts.unmuteEndpoint(id))

  def relationships(ids: List[Id]): F[List[Relationship]] =
    runner.run(Accounts.relationshipsEndpoint(ids))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Accounts[G] =
    new LiveAccounts[G](runner.mapK(transform))
