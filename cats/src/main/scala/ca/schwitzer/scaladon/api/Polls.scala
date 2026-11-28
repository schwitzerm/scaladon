package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, Poll}

/** The "Polls" section of the Mastodon API, as an algebra of values.
  *
  * A poll is reached through the `poll` field of a [[ca.schwitzer.scaladon.domain.Status]]; these
  * calls view it on its own or cast a vote. `F` is the effect; failures are raised as
  * `ApiException(ApiError)`.
  *
  * Viewing is public for a poll on a public status, so [[get]] never sends a token; like the other
  * public endpoints, the optional-token case (private parent status) is not expressible with the
  * current `Endpoint.authenticated` boolean. [[vote]] needs a token with `write:statuses`.
  */
trait Polls[F[_]]:

  /** `GET /api/v1/polls/{id}` — public for a poll on a public status. */
  def get(id: Id): F[Poll]

  /** `POST /api/v1/polls/{id}/votes`; `choices` are zero-based option indexes, sent as repeated
    * `choices[]` form fields. The server answers `422` when the poll has ended or the viewer has
    * already voted.
    */
  def vote(id: Id, choices: List[Int]): F[Poll]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Polls[G]

object Polls:

  def live[F[_]](runner: EndpointRunner[F]): Polls[F] =
    new LivePolls[F](runner)

  /** `GET /api/v1/polls/{id}` — public, no token. */
  def getEndpoint(id: Id): Endpoint[Poll] =
    Endpoint.get[Poll](s"/api/v1/polls/${id.value}", authenticated = false)

  /** `POST /api/v1/polls/{id}/votes` with one repeated `choices[]` field per selected option. */
  def voteEndpoint(id: Id, choices: List[Int]): Endpoint[Poll] =
    Endpoint.postForm[Poll](
      s"/api/v1/polls/${id.value}/votes",
      choices.map(choice => "choices[]" -> choice.toString)
    )

private final class LivePolls[F[_]](runner: EndpointRunner[F]) extends Polls[F]:

  def get(id: Id): F[Poll] =
    runner.run(Polls.getEndpoint(id))

  def vote(id: Id, choices: List[Int]): F[Poll] =
    runner.run(Polls.voteEndpoint(id, choices))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Polls[G] =
    new LivePolls[G](runner.mapK(transform))
