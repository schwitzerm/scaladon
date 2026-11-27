package ca.schwitzer.scaladon.models

import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Status(id: Int,
                  uri: String,
                  url: String,
                  account: Account,
                  inReplyToId: Option[Int],
                  inReplyToAccountId: Option[Int],
                  reblog: Option[Status],
                  content: String,
                  createdAt: DateTime,
                  reblogsCount: Int,
                  favouritesCount: Int,
                  reblogged: Boolean,
                  favourited: Boolean,
                  sensitive: Boolean,
                  spoilerText: Option[String],
                  visibility: StatusVisibility,
                  mediaAttachments: Seq[Attachment],
                  mentions: Seq[Mention],
                  tags: Seq[Tag],
                  application: Option[Application])

object Status {
  import ca.schwitzer.scaladon.dateReads

  implicit val reads: Reads[Status] = (
    (__ \ "id").read[Int] and
    (__ \ "uri").read[String] and
    (__ \ "url").read[String] and
    (__ \ "account").read[Account] and
    (__ \ "in_reply_to_id").readNullable[Int] and
    (__ \ "in_reply_to_account_id").readNullable[Int] and
    (__ \ "reblog").lazyReadNullable[Status](reads) and
    (__ \ "content").read[String] and
    (__ \ "created_at").read[DateTime] and
    (__ \ "reblogs_count").read[Int] and
    (__ \ "favourites_count").read[Int] and
    (__ \ "reblogged").readWithDefault[Boolean](false) and
    (__ \ "favourited").readWithDefault[Boolean](false) and
    (__ \ "sensitive").readWithDefault[Boolean](false) and
    (__ \ "spoiler_text").read[String].map { str =>
      if(str.isEmpty) None
      else Some(str)
    } and
    (__ \ "visibility").read[StatusVisibility] and
    (__ \ "media_attachments").read[Seq[Attachment]] and
    (__ \ "mentions").read[Seq[Mention]] and
    (__ \ "tags").read[Seq[Tag]] and
    (__ \ "application").readNullable[Application]
  )(Status.apply _)
}

sealed trait StatusVisibility { self =>
  override def toString: String = {
    self match {
      case StatusVisibilities.Default => ""
      case StatusVisibilities.Direct => "direct"
      case StatusVisibilities.Private => "private"
      case StatusVisibilities.Public => "public"
      case StatusVisibilities.Unlisted => "unlisted"
    }
  }
}

object StatusVisibility {
  implicit val reads: Reads[StatusVisibility] = __.read[String].map {
    case "" => StatusVisibilities.Default
    case "direct" => StatusVisibilities.Direct
    case "private" => StatusVisibilities.Private
    case "unlisted" => StatusVisibilities.Unlisted
    case "public" => StatusVisibilities.Public
  }
}

object StatusVisibilities {
  final case object Default extends StatusVisibility
  final case object Direct extends StatusVisibility
  final case object Private extends StatusVisibility
  final case object Public extends StatusVisibility
  final case object Unlisted extends StatusVisibility
}

