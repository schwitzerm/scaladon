package ca.schwitzer.scaladon.http4s

import cats.effect.{Async, Resource}
import fs2.io.net.Network

import ca.schwitzer.scaladon.Mastodon
import ca.schwitzer.scaladon.http.{BearerToken, Host}

/** Convenience constructors that pair the http4s interpreter with the client.
  *
  * The core `Mastodon` type deliberately does not depend on http4s; this is the one import a user
  * needs for the bundled transport.
  */
object MastodonHttp4s:

  /** A client backed by an ember connection pool, released with the `Resource`. */
  def resource[F[_]: Async: Network](host: Host, token: BearerToken): Resource[F, Mastodon[F]] =
    Http4sBackend.resource[F].map(backend => Mastodon.live(backend, host, token))
