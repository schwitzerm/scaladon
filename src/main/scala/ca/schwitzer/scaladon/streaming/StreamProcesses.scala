package ca.schwitzer.scaladon.streaming

sealed trait StreamProcess

object StreamProcesses {
  final case class StreamEvent(eventType: String) extends StreamProcess
  final case class StreamPayload(data: String) extends StreamProcess
}
