package ca.schwitzer.scaladon.domain

import java.time.Instant

/** One answer in a [[Poll]]. */
final case class PollOption(title: String, votesCount: Int)

/** A poll attached to a status.
  *
  * `expiresAt` is absent for a poll that never expires; `votersCount` and `voted` are only present
  * for an authenticated viewer. `ownVotes` indexes the options the viewer chose.
  */
final case class Poll(
    id: Id,
    expiresAt: Option[Instant],
    expired: Boolean,
    multiple: Boolean,
    votesCount: Int,
    votersCount: Option[Int],
    voted: Option[Boolean],
    ownVotes: Option[List[Int]],
    options: List[PollOption]
)
