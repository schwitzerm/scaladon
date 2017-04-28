package ca.schwitzer.scaladon.streaming

import ca.schwitzer.scaladon.models.mastodon.{Notification, Status}

sealed trait StreamResponse

object StreamResponses {

  final case class UpdateResponse(status: Status) extends StreamResponse

  final case class NotificationResponse(notification: Notification) extends StreamResponse

  final case class DeleteResponse(id: Int) extends StreamResponse

}
