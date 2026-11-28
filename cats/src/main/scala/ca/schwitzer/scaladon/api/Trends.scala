package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Status, Tag, TrendLink}

/** The "Trends" section of the Mastodon API (v1, 3.5+ paths).
  *
  * Every method is public: endpoints are constructed with `authenticated = false`, so no token is
  * attached even when the runner has one, and a server with trends disabled answers `404`. Results
  * are ordered by an internal trending score, not chronologically or lexically, and the scores are
  * recalculated periodically.
  *
  * `limit` and `offset` are both optional and omitted when absent: the server defaults are 10 for
  * tags and links, 20 for statuses, and its maxima are 20/40/20 respectively. A larger `limit` is
  * clamped by the server rather than rejected.
  */
trait Trends[F[_]]:

  /** `GET /api/v1/trends/tags` — public. The deprecated `GET /api/v1/trends` alias is not used. */
  def tags(limit: Option[Int] = None, offset: Option[Int] = None): F[List[Tag]]

  /** `GET /api/v1/trends/statuses` — public. */
  def statuses(limit: Option[Int] = None, offset: Option[Int] = None): F[List[Status]]

  /** `GET /api/v1/trends/links` — public. */
  def links(limit: Option[Int] = None, offset: Option[Int] = None): F[List[TrendLink]]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Trends[G]

object Trends:

  def live[F[_]](runner: EndpointRunner[F]): Trends[F] =
    new LiveTrends[F](runner)

  /** `GET /api/v1/trends/tags` — public, no token is attached. */
  def tagsEndpoint(limit: Option[Int], offset: Option[Int]): Endpoint[List[Tag]] =
    Endpoint.get[List[Tag]](
      "/api/v1/trends/tags",
      query = pagination(limit, offset),
      authenticated = false
    )

  /** `GET /api/v1/trends/statuses` — public, no token is attached. */
  def statusesEndpoint(limit: Option[Int], offset: Option[Int]): Endpoint[List[Status]] =
    Endpoint.get[List[Status]](
      "/api/v1/trends/statuses",
      query = pagination(limit, offset),
      authenticated = false
    )

  /** `GET /api/v1/trends/links` — public, no token is attached. */
  def linksEndpoint(limit: Option[Int], offset: Option[Int]): Endpoint[List[TrendLink]] =
    Endpoint.get[List[TrendLink]](
      "/api/v1/trends/links",
      query = pagination(limit, offset),
      authenticated = false
    )

  /** Both parameters are optional and sent only when present, so no empty query string is built. */
  private def pagination(limit: Option[Int], offset: Option[Int]): List[(String, String)] =
    limit.toList.map(value => "limit" -> value.toString) ++
      offset.toList.map(value => "offset" -> value.toString)

private final class LiveTrends[F[_]](runner: EndpointRunner[F]) extends Trends[F]:

  def tags(limit: Option[Int], offset: Option[Int]): F[List[Tag]] =
    runner.run(Trends.tagsEndpoint(limit, offset))

  def statuses(limit: Option[Int], offset: Option[Int]): F[List[Status]] =
    runner.run(Trends.statusesEndpoint(limit, offset))

  def links(limit: Option[Int], offset: Option[Int]): F[List[TrendLink]] =
    runner.run(Trends.linksEndpoint(limit, offset))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Trends[G] =
    new LiveTrends[G](runner.mapK(transform))
