package ca.schwitzer.scaladon.domain

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

/** Every way an API call can fail, as structured data.
  *
  * The cases exist to be branched on: authentication, a missing record, rate limiting and a
  * malformed response need different caller action. HTTP statuses that do not deserve their own
  * case stay in [[ApiError.Http]] with the status as a value, so the model does not grow a case per
  * RFC number.
  */
enum ApiError:

  /** The request never produced an HTTP response: DNS, TLS, connection, timeout. */
  case Transport(cause: Throwable)

  /** Any non-success status that is not rate limiting. `message` is Mastodon's `{"error": "…"}`. */
  case Http(status: HttpStatus, detail: Option[String], body: String)

  /** HTTP 429. `retryAfter` comes from the `Retry-After` header, in seconds. */
  case RateLimited(retryAfter: Option[FiniteDuration], detail: Option[String])

  /** A success status whose JSON did not decode into the expected type. */
  case Decode(detail: String)

  /** The endpoint requires a token and the client was built without one. */
  case NotAuthenticated(path: String)

  def message: String = this match
    case Transport(cause) =>
      Option(cause.getMessage).filter(_.nonEmpty) match
        case Some(detail) => s"Transport failure: $detail"
        case None         => "Transport failure"
    case Http(status, detail, body) =>
      val shown = detail.filter(_.nonEmpty).orElse(Option(body).map(_.trim).filter(_.nonEmpty))
      s"HTTP ${status.code}" + shown.fold("")(value => s": $value")
    case RateLimited(retryAfter, detail) =>
      val wait = retryAfter.fold("")(value => s" (retry after ${value.toSeconds}s)")
      s"HTTP 429, rate limited$wait" + detail.filter(_.nonEmpty).fold("")(m => s": $m")
    case Decode(detail)        => s"Could not decode response: $detail"
    case NotAuthenticated(path) => s"No access token configured for $path"

  /** Whether repeating the same request could succeed. Useful for retry/backoff policies. */
  def retryable: Boolean = this match
    case Transport(_)             => true
    case RateLimited(_, _)        => true
    case Http(status, _, _)       => status.code >= 500 || status.code == 408
    case Decode(_)                => false
    case NotAuthenticated(_)      => false

  def isNotFound: Boolean = this match
    case Http(status, _, _) => status.code == 404
    case _                  => false

  def isUnauthorized: Boolean = this match
    case Http(status, _, _) => status.code == 401
    case _                  => false

  /** The underlying transport failure, if this error wraps one. */
  def underlying: Option[Throwable] = this match
    case Transport(cause) => Some(cause)
    case _                => None

object ApiError:
  /** Recover the typed error from anything thrown on the effect's error channel. */
  def fromThrowable(t: Throwable): Option[ApiError] = t match
    case exception: ApiException => Some(exception.error)
    case _                       => None

/** The single exception type the client raises.
  *
  * One channel: every expected failure is raised inside `F` (so `timeout`, `retry`,
  * `handleErrorWith` and `guarantee` compose). The payload is the pure [[ApiError]] ADT, so callers
  * who want values use `.attempt` and match, or `attemptApi`. No stack trace is captured: these are
  * expected outcomes, not defects.
  */
final case class ApiException(error: ApiError)
    extends RuntimeException(error.message, error.underlying.orNull)
    with NoStackTrace
