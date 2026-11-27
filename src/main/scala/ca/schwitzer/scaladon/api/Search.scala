package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{SearchResults, SearchType}

/** The "Search" section of the Mastodon API.
  *
  * `search` returns `F[SearchResults]`; expected failures are raised inside `F` as
  * `ApiException(ApiError)`, so `attemptApi` is the way to get a value instead.
  *
  * Access is decided by the request, not by the endpoint: a plain search is public, but `resolve`
  * and `offset` are only honoured for an authenticated caller (Mastodon 4.0+). The endpoint
  * therefore carries a token exactly when one of those two flags is set — an anonymous client
  * asking for `resolve` or `offset` gets `ApiError.NotAuthenticated` before any request is sent,
  * while a plain query works anonymously and with a token. This is the one shape the boolean
  * `Endpoint.authenticated` can express; a server that changes the rule would need the optional
  * token contract.
  *
  * Full-text status search needs both an ElasticSearch backend and a token; accounts and hashtags
  * are searchable regardless. An empty category is not proof that nothing matches.
  */
trait Search[F[_]]:

  /** `GET /api/v2/search` — public without `resolve`/`offset`, `read:search` with them.
    *
    * `q` is required. `kind` restricts the categories the server searches and is also what makes
    * `offset` meaningful: without a `type`, the server ignores `offset`. `following` keeps only
    * accounts the authenticated user follows. `limit` is per category and capped by the server at
    * 40. Cursors (`max_id`/`min_id`) are not ported because `offset` is the documented pagination
    * for this endpoint.
    */
  def search(
      q: String,
      kind: Option[SearchType] = None,
      resolve: Boolean = false,
      following: Boolean = false,
      offset: Option[Int] = None,
      limit: Option[Int] = None
  ): F[SearchResults]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Search[G]

object Search:

  def live[F[_]](runner: EndpointRunner[F]): Search[F] =
    new LiveSearch[F](runner)

  /** `GET /api/v2/search`; the token is attached only when `resolve` or `offset` needs one. */
  def searchEndpoint(
      q: String,
      kind: Option[SearchType],
      resolve: Boolean,
      following: Boolean,
      offset: Option[Int],
      limit: Option[Int]
  ): Endpoint[SearchResults] =
    Endpoint.get[SearchResults](
      "/api/v2/search",
      query =
        List("q" -> q) ++
          kind.toList.map(value => "type" -> value.wire) ++
          flag("resolve", resolve) ++
          flag("following", following) ++
          offset.toList.map(value => "offset" -> value.toString) ++
          limit.toList.map(value => "limit" -> value.toString),
      authenticated = resolve || offset.isDefined
    )

  /** Only a set flag is sent: the server default for both is `false`. */
  private def flag(name: String, on: Boolean): List[(String, String)] =
    if on then List(name -> "true") else Nil

private final class LiveSearch[F[_]](runner: EndpointRunner[F]) extends Search[F]:

  def search(
      q: String,
      kind: Option[SearchType],
      resolve: Boolean,
      following: Boolean,
      offset: Option[Int],
      limit: Option[Int]
  ): F[SearchResults] =
    runner.run(Search.searchEndpoint(q, kind, resolve, following, offset, limit))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Search[G] =
    new LiveSearch[G](runner.mapK(transform))
