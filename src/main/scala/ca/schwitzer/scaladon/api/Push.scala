package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}
import io.circe.{Decoder, Json}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{ApiError, PushAlertSettings, PushPolicy, PushSubscription}
import ca.schwitzer.scaladon.http.HttpMethod

/** The "Push" section: the Web Push API subscription attached to the current access token.
  *
  * A token can have at most one subscription, and creating a new one deletes the old one. All
  * request bodies are `application/x-www-form-urlencoded` because the API documents nested form
  * parameters (`subscription[keys][p256dh]`, `data[alerts][mention]`, `data[policy]`). The
  * serializer also returns `server_key` (the instance's VAPID key) and `policy`; the PUT method
  * nests `policy` under `data` in the server's controller even though one documentation page
  * lists it at the top level.
  */
trait Push[F[_]]:

  /** `POST /api/v1/push/subscription`; replaces any existing subscription for this token.
    *
    * `standard` selects RFC 8030/8291/8292 framing (Mastodon 4.4+). Only the alerts set to
    * `Some(...)` are sent; absent ones keep the server default (`false`).
    */
  def create(
      endpoint: String,
      p256dh: String,
      auth: String,
      standard: Option[Boolean] = None,
      alerts: PushAlertSettings = PushAlertSettings(),
      policy: Option[PushPolicy] = None
  ): F[PushSubscription]

  /** `GET /api/v1/push/subscription`; raises `ApiError.Http(404)` when none exists. */
  def get: F[PushSubscription]

  /** `PUT /api/v1/push/subscription`; only the alert flags and policy supplied are sent. */
  def update(
      alerts: PushAlertSettings = PushAlertSettings(),
      policy: Option[PushPolicy] = None
  ): F[PushSubscription]

  /** `DELETE /api/v1/push/subscription`; succeeds whether or not a subscription existed. */
  def delete: F[Unit]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Push[G]

object Push:

  def live[F[_]](runner: EndpointRunner[F]): Push[F] =
    new LivePush[F](runner)

  /** `POST /api/v1/push/subscription`. */
  def createEndpoint(
      endpoint: String,
      p256dh: String,
      auth: String,
      standard: Option[Boolean],
      alerts: PushAlertSettings,
      policy: Option[PushPolicy]
  ): Endpoint[PushSubscription] =
    Endpoint.postForm[PushSubscription](
      "/api/v1/push/subscription",
      subscriptionFields(endpoint, p256dh, auth) ++
        standard.toList.map(value => "subscription[standard]" -> value.toString) ++
        alertFields(alerts) ++
        policyFields(policy)
    )

  /** `GET /api/v1/push/subscription`. */
  def getEndpoint: Endpoint[PushSubscription] =
    Endpoint.get[PushSubscription]("/api/v1/push/subscription")

  /** `PUT /api/v1/push/subscription`; the foundation has no `putForm`, so the value is built here. */
  def updateEndpoint(
      alerts: PushAlertSettings,
      policy: Option[PushPolicy]
  ): Endpoint[PushSubscription] =
    Endpoint(
      HttpMethod.PUT,
      "/api/v1/push/subscription",
      Nil,
      None,
      authenticated = true,
      decode = decodeWith[PushSubscription],
      form = Some(alertFields(alerts) ++ policyFields(policy))
    )

  /** `DELETE /api/v1/push/subscription`. */
  def deleteEndpoint: Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, "/api/v1/push/subscription")

  private def subscriptionFields(
      endpoint: String,
      p256dh: String,
      auth: String
  ): List[(String, String)] =
    List(
      "subscription[endpoint]" -> endpoint,
      "subscription[keys][p256dh]" -> p256dh,
      "subscription[keys][auth]" -> auth
    )

  /** The alert keys, in the order the API documents them; absent flags are omitted. */
  private val alertFieldsByName: List[(String, PushAlertSettings => Option[Boolean])] =
    List(
      "mention" -> (_.mention),
      "quote" -> (_.quote),
      "status" -> (_.status),
      "reblog" -> (_.reblog),
      "follow" -> (_.follow),
      "follow_request" -> (_.followRequest),
      "favourite" -> (_.favourite),
      "poll" -> (_.poll),
      "update" -> (_.update),
      "quoted_update" -> (_.quotedUpdate),
      "admin.sign_up" -> (_.adminSignUp),
      "admin.report" -> (_.adminReport)
    )

  private def alertFields(alerts: PushAlertSettings): List[(String, String)] =
    alertFieldsByName.flatMap { case (name, get) =>
      get(alerts).map(value => s"data[alerts][$name]" -> value.toString)
    }

  private def policyFields(policy: Option[PushPolicy]): List[(String, String)] =
    policy.toList.map(value => "data[policy]" -> value.wire)

  private def decodeWith[A](using decoder: Decoder[A]): Json => Either[ApiError, A] =
    json => decoder.decodeJson(json).left.map(failure => ApiError.Decode(failure.message))

private final class LivePush[F[_]](runner: EndpointRunner[F]) extends Push[F]:

  def create(
      endpoint: String,
      p256dh: String,
      auth: String,
      standard: Option[Boolean],
      alerts: PushAlertSettings,
      policy: Option[PushPolicy]
  ): F[PushSubscription] =
    runner.run(Push.createEndpoint(endpoint, p256dh, auth, standard, alerts, policy))

  def get: F[PushSubscription] =
    runner.run(Push.getEndpoint)

  def update(
      alerts: PushAlertSettings,
      policy: Option[PushPolicy]
  ): F[PushSubscription] =
    runner.run(Push.updateEndpoint(alerts, policy))

  def delete: F[Unit] =
    runner.run(Push.deleteEndpoint)

  def mapK[G[_]: MonadThrow](transform: F ~> G): Push[G] =
    new LivePush[G](runner.mapK(transform))
