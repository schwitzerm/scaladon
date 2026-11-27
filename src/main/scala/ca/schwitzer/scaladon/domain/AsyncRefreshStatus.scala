package ca.schwitzer.scaladon.domain

/** State of an asynchronous refresh, as reported by `GET /api/v1_alpha/async_refreshes/:id`.
  *
  * An enum with a total `fromWire` instead of a `String`, matching [[Visibility]] (legacy #9): a
  * status this version does not know fails decoding loudly rather than leaking an unhandled string
  * into callers.
  */
enum AsyncRefreshState:
  case Running, Finished

  def wire: String = this match
    case Running  => "running"
    case Finished => "finished"

object AsyncRefreshState:
  def fromWire(value: String): Option[AsyncRefreshState] = value match
    case "running"  => Some(Running)
    case "finished" => Some(Finished)
    case _          => None

/** Polling result for the refresh announced by a `Mastodon-Async-Refresh` header.
  *
  * `id` is the header's opaque refresh token (a signed string, not a Mastodon object id); it stays
  * a plain `String` so it lines up with [[AsyncRefresh.id]]. `resultCount` is absent while a refresh
  * yields nothing and a `finished` refresh with `Some(0)` means it produced no new results, so the
  * difference between "unknown" and "zero" is preserved.
  */
final case class AsyncRefreshStatus(
    id: String,
    status: AsyncRefreshState,
    resultCount: Option[Int]
)
