package ca.schwitzer.scaladon.streaming

sealed trait StreamMessage

object StreamMessages {
  final case class  Event(eventType: String) extends StreamMessage
  final case class  Payload(data: String) extends StreamMessage
  final case object Heartbeat extends StreamMessage
}
