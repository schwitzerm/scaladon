package ca.schwitzer.scaladon.codec

import cats.syntax.traverse.*
import io.circe.{Decoder, DecodingFailure, Encoder, Json}

import java.time.Instant
import scala.util.Try

import ca.schwitzer.scaladon.domain.{Account, Id, Status, Visibility}

/** JSON codecs for the domain types.
  *
  * Codecs live here, not in the domain companions, so `domain` stays free of any JSON or HTTP
  * dependency. They are typeclass instances: anything that needs to decode `A` takes `Decoder[A]`.
  *
  * Wire conventions: snake_case keys, ids as JSON strings, timestamps as RFC 3339 `Instant`.
  * Declaration order matters — the derived instances capture the primitive instances below.
  */
object Codecs:

  given Encoder[Id] = Encoder.encodeString.contramap[Id](_.value)
  given Decoder[Id] = Decoder.decodeString.map(Id.apply)

  given Encoder[Visibility] = Encoder.encodeString.contramap[Visibility](_.wire)
  given Decoder[Visibility] =
    Decoder.decodeString.emap(value =>
      Visibility.fromWire(value).toRight(s"Unknown visibility '$value'")
    )

  given Encoder[Instant] = Encoder.encodeString.contramap[Instant](_.toString)
  given Decoder[Instant] =
    Decoder.decodeString.emap(value =>
      Try(Instant.parse(value)).toEither.left.map(e => s"Invalid timestamp '$value': ${e.getMessage}")
    )

  given Decoder[Account] = Decoder.forProduct10(
    "id",
    "username",
    "acct",
    "display_name",
    "note",
    "url",
    "avatar",
    "header",
    "locked",
    "created_at"
  )(Account.apply)

  given Decoder[Status] = status

  /** Recursive because `reblog` embeds another status; a `lazy val` lets the decoder refer to itself
    * while keeping the instance a single value.
    */
  private lazy val status: Decoder[Status] =
    Decoder.instance { cursor =>
      for
        id                 <- cursor.get[Id]("id")
        uri                <- cursor.get[String]("uri")
        url                <- cursor.get[Option[String]]("url").map(_.filter(_.nonEmpty))
        account            <- cursor.get[Account]("account")
        content            <- cursor.get[String]("content")
        createdAt          <- cursor.get[Instant]("created_at")
        visibility         <- cursor.get[Visibility]("visibility")
        inReplyToId        <- cursor.get[Option[Id]]("in_reply_to_id")
        inReplyToAccountId <- cursor.get[Option[Id]]("in_reply_to_account_id")
        spoilerText        <- cursor.get[Option[String]]("spoiler_text").map(_.filter(_.nonEmpty))
        sensitive          <- cursor.get[Option[Boolean]]("sensitive").map(_.getOrElse(false))
        reblogJson         <- cursor.get[Option[Json]]("reblog")
        reblog             <- decodeOption(reblogJson)
        reblogsCount       <- cursor.get[Int]("reblogs_count")
        favouritesCount    <- cursor.get[Int]("favourites_count")
        favourited         <- cursor.get[Option[Boolean]]("favourited").map(_.getOrElse(false))
        reblogged          <- cursor.get[Option[Boolean]]("reblogged").map(_.getOrElse(false))
      yield Status(
        id = id,
        uri = uri,
        url = url,
        account = account,
        content = content,
        createdAt = createdAt,
        visibility = visibility,
        inReplyToId = inReplyToId,
        inReplyToAccountId = inReplyToAccountId,
        spoilerText = spoilerText,
        sensitive = sensitive,
        reblog = reblog,
        reblogsCount = reblogsCount,
        favouritesCount = favouritesCount,
        favourited = favourited,
        reblogged = reblogged
      )
    }

  private def decodeOption(json: Option[Json]): Either[DecodingFailure, Option[Status]] =
    json.traverse(status.decodeJson)
