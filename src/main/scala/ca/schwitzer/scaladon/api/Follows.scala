package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.Relationship

/** Following by handle: `POST /api/v1/follows` resolves a remote account URI and follows it.
  *
  * Following an account whose id the caller already holds is [[Accounts.follow]]; this endpoint
  * exists for the case where only the `acct:user@domain` URI is known, so it takes the URI as a
  * required form field rather than a path id.
  */
trait Follows[F[_]]:

  /** `POST /api/v1/follows` with a required `uri` form field. */
  def follow(uri: String): F[Relationship]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Follows[G]

object Follows:

  def live[F[_]](runner: EndpointRunner[F]): Follows[F] =
    new LiveFollows[F](runner)

  /** `POST /api/v1/follows` — the account is addressed by its ActivityPub `uri`. */
  def followEndpoint(uri: String): Endpoint[Relationship] =
    Endpoint.postForm[Relationship]("/api/v1/follows", List("uri" -> uri))

private final class LiveFollows[F[_]](runner: EndpointRunner[F]) extends Follows[F]:

  def follow(uri: String): F[Relationship] =
    runner.run(Follows.followEndpoint(uri))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Follows[G] =
    new LiveFollows[G](runner.mapK(transform))
