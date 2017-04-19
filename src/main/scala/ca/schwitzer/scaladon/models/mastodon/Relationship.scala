package ca.schwitzer.scaladon.models.mastodon

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Relationship(id: Int,
                        following: Boolean,
                        followedBy: Boolean,
                        blocking: Boolean,
                        muting: Boolean,
                        requested: Boolean)

object Relationship {
  implicit val reads: Reads[Relationship] = (
    (__ \ "id").read[Int] and
    (__ \ "following").read[Boolean] and
    (__ \ "followed_by").read[Boolean] and
    (__ \ "blocking").read[Boolean] and
    (__ \ "muting").read[Boolean] and
    (__ \ "requested").read[Boolean]
  )(Relationship.apply _)
}
