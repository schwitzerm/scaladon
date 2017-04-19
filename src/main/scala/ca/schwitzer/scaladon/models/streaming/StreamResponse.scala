package ca.schwitzer.scaladon.models.streaming

import ca.schwitzer.scaladon.models.MastodonResponseError

sealed trait StreamResponse

object StreamResponses {
  final case object Heartbeat extends StreamResponse

  final case class Event(data: StreamData) extends StreamResponse
  final case class Error(error: MastodonResponseError) extends StreamResponse
}
