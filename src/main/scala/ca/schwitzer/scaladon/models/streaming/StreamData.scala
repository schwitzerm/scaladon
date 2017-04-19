package ca.schwitzer.scaladon.models.streaming

import ca.schwitzer.scaladon.models.{Notification, Status}

sealed trait Payload

object Payloads {
  case class StatusPayload(status: Status) extends Payload
  case class NotificationPayload(notification: Notification) extends Payload
  case class DeletePayload(statusId: Int) extends Payload
}

case class StreamData(event: String, data: Payload)
