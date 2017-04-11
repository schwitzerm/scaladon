package ca.schwitzer.scaladon.models

import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Account(id: Int,
                   username: String,
                   acct: String,
                   displayName: String,
                   note: String,
                   url: String,
                   avatar: String,
                   header: String,
                   locked: Boolean,
                   createdAt: DateTime,
                   followersCount: Int,
                   followingCount: Int,
                   statusesCount: Int)

object Account {
  import ca.schwitzer.scaladon.dateReads

  implicit val reads: Reads[Account] = (
    (JsPath \ "id").read[Int] and
    (JsPath \ "username").read[String] and
    (JsPath \ "acct").read[String] and
    (JsPath \ "display_name").read[String] and
    (JsPath \ "note").read[String] and
    (JsPath \ "url").read[String] and
    (JsPath \ "avatar").read[String] and
    (JsPath \ "header").read[String] and
    (JsPath \ "locked").read[Boolean] and
    (JsPath \ "created_at").read[DateTime] and
    (JsPath \ "followers_count").read[Int] and
    (JsPath \ "following_count").read[Int] and
    (JsPath \ "statuses_count").read[Int]
  )(Account.apply _)
}

case class AccountUpdateData(displayName: Option[String],
                             note: Option[String],
                             avatar: Option[String],
                             header: Option[String])

object AccountUpdateData {
  implicit val writes: Writes[AccountUpdateData] = (data: AccountUpdateData) => {
    val mappings = Seq(
      "display_name" -> data.displayName,
      "note" -> data.note,
      "avatar" -> data.avatar,
      "header" -> data.header
    ).collect { case (key, opt) if opt.nonEmpty => key -> Json.toJsFieldJsValueWrapper(opt.get) }

    Json.obj(mappings: _*)
  }
}
