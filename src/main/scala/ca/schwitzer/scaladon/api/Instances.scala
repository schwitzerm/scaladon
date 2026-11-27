package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.Instance

/** The "Instance" section: server metadata and the API version gate.
  *
  * `v2.apiVersions.mastodon` is the capability check every other namespace relies on; parse
  * `version` only for display. `configuration` carries the limits clients must not hard-code.
  */
trait Instances[F[_]]:
  def v2: F[Instance]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Instances[G]

object Instances:

  def live[F[_]](runner: EndpointRunner[F]): Instances[F] =
    new LiveInstances[F](runner)

  /** `GET /api/v2/instance` — public, no token. */
  def v2Endpoint: Endpoint[Instance] =
    Endpoint.get[Instance]("/api/v2/instance", authenticated = false)

private final class LiveInstances[F[_]](runner: EndpointRunner[F]) extends Instances[F]:

  def v2: F[Instance] =
    runner.run(Instances.v2Endpoint)

  def mapK[G[_]: MonadThrow](transform: F ~> G): Instances[G] =
    new LiveInstances[G](runner.mapK(transform))
