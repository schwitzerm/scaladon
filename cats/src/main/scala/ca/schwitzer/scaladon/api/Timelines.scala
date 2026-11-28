package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, Status}

/** The "Timelines" section of the Mastodon API.
  *
  * Every method returns `F[List[Status]]`; expected failures are raised inside `F` as
  * `ApiException(ApiError)`, so `attemptApi` is the way to get a value instead.
  *
  * Pagination is cursor-based: `maxId` is the upper bound (newer results are not returned),
  * `sinceId` the lower bound, and `minId` sets a cursor and pages forward; `maxId` and `minId`
  * may be combined (since 3.3). `limit` is capped by the server at 40.
  *
  * Access control (Mastodon 4.5+): `home` and `list` need a user token and raise
  * `ApiError.NotAuthenticated` on an anonymous client before any request is sent. `publicTimeline`
  * and `tag` are public endpoints: they send no token, so a server that requires authentication
  * for them answers `401`, which surfaces as `ApiError.Http(HttpStatus(401), …)`. There is no
  * second error channel for access control; a caller that wants to know in advance reads
  * `Instances.v2` and checks `Instance.configuration.timelinesAccess` — `liveFeeds` for
  * `home`/`publicTimeline`, `hashtagFeeds` for `tag` — against `TimelineAccess.Disabled` (feed
  * off) and `TimelineAccess.Authenticated` (token required). An enabled-but-disabled feed can
  * also answer `200 []`, so an empty list is not proof that nothing was posted.
  *
  * `home` may answer `206 Partial Content` while the feed is regenerating; that is still a 2xx
  * success and decodes to the statuses received so far.
  */
trait Timelines[F[_]]:

  /** `GET /api/v1/timelines/home` — requires `read:statuses`.
    *
    * `excludeDirect`, `excludeReblogs`, `excludeQuotes` and `excludeReplies` are the 5.0 filters
    * (API version 12); a `false` flag is omitted rather than sent, because omission is the server
    * default.
    */
  def home(
      excludeDirect: Boolean = false,
      excludeReblogs: Boolean = false,
      excludeQuotes: Boolean = false,
      excludeReplies: Boolean = false,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Status]]

  /** `GET /api/v1/timelines/public` — public, no token.
    *
    * `local` and `remote` request one side of the federation; sending both is contradictory and
    * left to the server to interpret.
    */
  def publicTimeline(
      local: Boolean = false,
      remote: Boolean = false,
      onlyMedia: Boolean = false,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Status]]

  /** `GET /api/v1/timelines/tag/{hashtag}` — public, no token.
    *
    * `tag` is the hashtag name without the leading `#`; it is percent-encoded as one path segment.
    * `any`, `all` and `none` are additional tag filters (since 2.7) sent as repeated `any[]`,
    * `all[]`, `none[]` parameters. The server applies only a limited number of them (four by
    * default), so extra values are silently dropped there.
    */
  def tag(
      tag: String,
      any: List[String] = Nil,
      all: List[String] = Nil,
      none: List[String] = Nil,
      local: Boolean = false,
      remote: Boolean = false,
      onlyMedia: Boolean = false,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Status]]

  /** `GET /api/v1/timelines/list/{list_id}` — requires `read:lists`; a list owned by someone else
    * answers `404`, which surfaces as `ApiError.Http` with `isNotFound`.
    */
  def list(
      listId: Id,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Status]]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Timelines[G]

object Timelines:

  def live[F[_]](runner: EndpointRunner[F]): Timelines[F] =
    new LiveTimelines[F](runner)

  /** `GET /api/v1/timelines/home` — authenticated. */
  def homeEndpoint(
      excludeDirect: Boolean,
      excludeReblogs: Boolean,
      excludeQuotes: Boolean,
      excludeReplies: Boolean,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): Endpoint[List[Status]] =
    Endpoint.get[List[Status]](
      "/api/v1/timelines/home",
      query =
        flag("exclude_direct", excludeDirect) ++
          flag("exclude_reblogs", excludeReblogs) ++
          flag("exclude_quotes", excludeQuotes) ++
          flag("exclude_replies", excludeReplies) ++
          pagination(maxId, sinceId, minId, limit)
    )

  /** `GET /api/v1/timelines/public` — public, no token is attached. */
  def publicEndpoint(
      local: Boolean,
      remote: Boolean,
      onlyMedia: Boolean,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): Endpoint[List[Status]] =
    Endpoint.get[List[Status]](
      "/api/v1/timelines/public",
      query =
        flag("local", local) ++
          flag("remote", remote) ++
          flag("only_media", onlyMedia) ++
          pagination(maxId, sinceId, minId, limit),
      authenticated = false
    )

  /** `GET /api/v1/timelines/tag/{hashtag}` — public, no token is attached. */
  def tagEndpoint(
      tag: String,
      any: List[String],
      all: List[String],
      none: List[String],
      local: Boolean,
      remote: Boolean,
      onlyMedia: Boolean,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): Endpoint[List[Status]] =
    Endpoint.get[List[Status]](
      s"/api/v1/timelines/tag/${encodePathSegment(tag)}",
      query =
        repeated("any[]", any) ++
          repeated("all[]", all) ++
          repeated("none[]", none) ++
          flag("local", local) ++
          flag("remote", remote) ++
          flag("only_media", onlyMedia) ++
          pagination(maxId, sinceId, minId, limit),
      authenticated = false
    )

  /** `GET /api/v1/timelines/list/{list_id}` — authenticated. */
  def listEndpoint(
      listId: Id,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): Endpoint[List[Status]] =
    Endpoint.get[List[Status]](
      s"/api/v1/timelines/list/${listId.value}",
      query = pagination(maxId, sinceId, minId, limit)
    )

  /** Only a set flag is sent: the server default for all of these is `false`. */
  private def flag(name: String, on: Boolean): List[(String, String)] =
    if on then List(name -> "true") else Nil

  private def repeated(name: String, values: List[String]): List[(String, String)] =
    values.map(value => name -> value)

  private def pagination(
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): List[(String, String)] =
    maxId.toList.map(id => "max_id" -> id.value) ++
      sinceId.toList.map(id => "since_id" -> id.value) ++
      minId.toList.map(id => "min_id" -> id.value) ++
      limit.toList.map(value => "limit" -> value.toString)

  /** One path segment: `+` from the form encoder is not valid in a path, so it becomes `%20`. */
  private def encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private final class LiveTimelines[F[_]](runner: EndpointRunner[F]) extends Timelines[F]:

  def home(
      excludeDirect: Boolean,
      excludeReblogs: Boolean,
      excludeQuotes: Boolean,
      excludeReplies: Boolean,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[Status]] =
    runner.run(
      Timelines.homeEndpoint(
        excludeDirect,
        excludeReblogs,
        excludeQuotes,
        excludeReplies,
        maxId,
        sinceId,
        minId,
        limit
      )
    )

  def publicTimeline(
      local: Boolean,
      remote: Boolean,
      onlyMedia: Boolean,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[Status]] =
    runner.run(Timelines.publicEndpoint(local, remote, onlyMedia, maxId, sinceId, minId, limit))

  def tag(
      tag: String,
      any: List[String],
      all: List[String],
      none: List[String],
      local: Boolean,
      remote: Boolean,
      onlyMedia: Boolean,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[Status]] =
    runner.run(
      Timelines.tagEndpoint(
        tag,
        any,
        all,
        none,
        local,
        remote,
        onlyMedia,
        maxId,
        sinceId,
        minId,
        limit
      )
    )

  def list(
      listId: Id,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[Status]] =
    runner.run(Timelines.listEndpoint(listId, maxId, sinceId, minId, limit))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Timelines[G] =
    new LiveTimelines[G](runner.mapK(transform))
