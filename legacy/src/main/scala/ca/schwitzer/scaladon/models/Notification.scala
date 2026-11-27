package ca.schwitzer.scaladon.models

import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Notification(id: Int,
                        notificationType: NotificationType,
                        createdAt: DateTime,
                        account: Account,
                        status: Option[Status])

object Notification {
  import ca.schwitzer.scaladon.dateReads

  implicit val reads: Reads[Notification] = (
    (__ \ "id").read[Int] and
    (__ \ "type").read[NotificationType] and
    (__ \ "created_at").read[DateTime] and
    (__ \ "account").read[Account] and
    (__ \ "status").readNullable[Status]
  )(Notification.apply _)
}

sealed trait NotificationType { self =>
  override def toString: String = self match {
    case NotificationTypes.Mention => "mention"
    case NotificationTypes.Reblog => "reblog"
    case NotificationTypes.Favourite => "favourite"
    case NotificationTypes.Follow => "follow"
  }
}

object NotificationType {
  implicit val reads: Reads[NotificationType] = __.read[String].map {
    case "mention" => NotificationTypes.Mention
    case "reblog" => NotificationTypes.Reblog
    case "favourite" => NotificationTypes.Favourite
    case "follow" => NotificationTypes.Follow
  }
}

object NotificationTypes {
  final case object Mention extends NotificationType
  final case object Reblog extends NotificationType
  final case object Favourite extends NotificationType
  final case object Follow extends NotificationType
}
