package ca.schwitzer.scaladon.domain

/** Which users' notifications reach a Web Push endpoint (`data[policy]`, Mastodon 3.4+).
  *
  * The wire value for [[PushPolicy.Off]] is `"none"`; the case is named `Off` so it cannot be
  * confused with `scala.None`. Unknown values fail decoding.
  */
enum PushPolicy(val wire: String):
  case All      extends PushPolicy("all")
  case Followed extends PushPolicy("followed")
  case Follower extends PushPolicy("follower")
  case Off      extends PushPolicy("none")

object PushPolicy:
  def all: List[PushPolicy] = values.toList

  /** Total inverse of [[PushPolicy.wire]]; `None` for values this version does not know. */
  def fromWire(value: String): Option[PushPolicy] = all.find(_.wire == value)

/** Which notification kinds a subscription delivers, as returned by the server.
  *
  * Every flag defaults to `false`, matching the API default, so a server that omits a kind is
  * not confused with one that disabled it explicitly. The entity gained `quote`/`quoted_update`
  * in Mastodon 4.5 (API version 7) and the admin alerts in 3.5/4.0.
  */
final case class PushAlerts(
    mention: Boolean = false,
    quote: Boolean = false,
    status: Boolean = false,
    reblog: Boolean = false,
    follow: Boolean = false,
    followRequest: Boolean = false,
    favourite: Boolean = false,
    poll: Boolean = false,
    update: Boolean = false,
    quotedUpdate: Boolean = false,
    adminSignUp: Boolean = false,
    adminReport: Boolean = false
)

/** The alerts a caller wants to change, with `None` meaning "leave this one alone".
  *
  * `PUT /api/v1/push/subscription` replaces the stored alert map, so every absent field must be
  * omitted from the form rather than sent as `false`; that is why the request type is tri-state
  * while the response type [[PushAlerts]] is plain booleans.
  */
final case class PushAlertSettings(
    mention: Option[Boolean] = None,
    quote: Option[Boolean] = None,
    status: Option[Boolean] = None,
    reblog: Option[Boolean] = None,
    follow: Option[Boolean] = None,
    followRequest: Option[Boolean] = None,
    favourite: Option[Boolean] = None,
    poll: Option[Boolean] = None,
    update: Option[Boolean] = None,
    quotedUpdate: Option[Boolean] = None,
    adminSignUp: Option[Boolean] = None,
    adminReport: Option[Boolean] = None
)

/** A Web Push API subscription (`/api/v1/push/subscription`).
  *
  * Each access token has at most one subscription. `id` is a database integer (not the string id
  * the rest of the API uses), `serverKey` is the instance's VAPID public key from
  * `configuration.vapid.public_key`, and `standard` selects RFC 8030/8291/8292 framing over the
  * earlier draft framing (Mastodon 4.4+). `policy` defaults to [[PushPolicy.All]] when a server
  * omits it.
  */
final case class PushSubscription(
    id: Long,
    endpoint: String,
    standard: Boolean,
    alerts: PushAlerts,
    serverKey: String,
    policy: PushPolicy
)
