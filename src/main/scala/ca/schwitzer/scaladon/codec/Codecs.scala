package ca.schwitzer.scaladon.codec

import cats.syntax.traverse.*
import io.circe.{Decoder, DecodingFailure, Encoder, Json}

import java.time.Instant
import scala.util.Try

import ca.schwitzer.scaladon.domain.{
  Account,
  AccountsConfiguration,
  ApiVersions,
  CollectionsConfiguration,
  FeedAccess,
  Id,
  Instance,
  InstanceConfiguration,
  InstanceUrls,
  MediaConfiguration,
  PollsConfiguration,
  Registrations,
  Status,
  StatusesConfiguration,
  TimelineAccess,
  TimelinesAccess,
  TranslationConfiguration,
  VapidConfiguration,
  Visibility
}

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

  // --- Instance (GET /api/v2/instance) ---

  given Decoder[TimelineAccess] = Decoder.decodeString.emap(value =>
    TimelineAccess.fromWire(value).toRight(s"Unknown timeline access '$value'")
  )

  given Decoder[FeedAccess] = Decoder.forProduct2("local", "remote")(FeedAccess.apply)

  given Decoder[TimelinesAccess] = Decoder.forProduct3(
    "live_feeds",
    "hashtag_feeds",
    "trending_link_feeds"
  )(TimelinesAccess.apply)

  given Decoder[InstanceUrls] = Decoder.instance { cursor =>
    for
      streaming <- cursor.get[String]("streaming")
      status    <- cursor.get[Option[String]]("status")
      about     <- cursor.get[String]("about")
      privacy   <- cursor.get[Option[String]]("privacy_policy")
      terms     <- cursor.get[Option[String]]("terms_of_service")
    yield InstanceUrls(streaming, status, about, privacy, terms)
  }

  given Decoder[VapidConfiguration] =
    Decoder.forProduct1("public_key")(VapidConfiguration.apply)

  given Decoder[AccountsConfiguration] = Decoder.forProduct9(
    "max_display_name_length",
    "max_note_length",
    "max_avatar_description_length",
    "max_header_description_length",
    "max_featured_tags",
    "max_pinned_statuses",
    "max_profile_fields",
    "profile_field_name_limit",
    "profile_field_value_limit"
  )(AccountsConfiguration.apply)

  given Decoder[StatusesConfiguration] = Decoder.forProduct3(
    "max_characters",
    "max_media_attachments",
    "characters_reserved_per_url"
  )(StatusesConfiguration.apply)

  given Decoder[MediaConfiguration] = Decoder.forProduct7(
    "description_limit",
    "supported_mime_types",
    "image_size_limit",
    "image_matrix_limit",
    "video_size_limit",
    "video_matrix_limit",
    "video_frame_rate_limit"
  )(MediaConfiguration.apply)

  given Decoder[PollsConfiguration] = Decoder.forProduct4(
    "max_options",
    "max_characters_per_option",
    "min_expiration",
    "max_expiration"
  )(PollsConfiguration.apply)

  given Decoder[TranslationConfiguration] =
    Decoder.forProduct1("enabled")(TranslationConfiguration.apply)

  given Decoder[CollectionsConfiguration] = Decoder.forProduct3(
    "max_items",
    "max_name_length",
    "max_description_length"
  )(CollectionsConfiguration.apply)

  given Decoder[InstanceConfiguration] = Decoder.instance { cursor =>
    for
      urls        <- cursor.get[InstanceUrls]("urls")
      vapid       <- cursor.get[VapidConfiguration]("vapid")
      accounts    <- cursor.get[AccountsConfiguration]("accounts")
      statuses    <- cursor.get[StatusesConfiguration]("statuses")
      media       <- cursor.get[MediaConfiguration]("media_attachments")
      polls       <- cursor.get[PollsConfiguration]("polls")
      translation <- cursor.get[TranslationConfiguration]("translation")
      timelines   <- cursor.get[TimelinesAccess]("timelines_access")
      limited     <- cursor.get[Boolean]("limited_federation")
      collections <- cursor.get[CollectionsConfiguration]("collections")
    yield InstanceConfiguration(
      urls = urls,
      vapid = vapid,
      accounts = accounts,
      statuses = statuses,
      mediaAttachments = media,
      polls = polls,
      translation = translation,
      timelinesAccess = timelines,
      limitedFederation = limited,
      collections = collections
    )
  }

  given Decoder[Registrations] = Decoder.instance { cursor =>
    for
      enabled          <- cursor.get[Boolean]("enabled")
      approvalRequired <- cursor.get[Boolean]("approval_required")
      reasonRequired   <- cursor.get[Option[Boolean]]("reason_required").map(_.getOrElse(false))
      minAge           <- cursor.get[Option[Int]]("min_age")
      message          <- cursor.get[Option[String]]("message")
      url              <- cursor.get[Option[String]]("url")
      oauth            <- cursor.get[Option[Boolean]]("oauth").map(_.getOrElse(false))
    yield Registrations(
      enabled = enabled,
      approvalRequired = approvalRequired,
      reasonRequired = reasonRequired,
      minAge = minAge,
      message = message,
      url = url,
      oauth = oauth
    )
  }

  given Decoder[ApiVersions] = Decoder.forProduct1("mastodon")(ApiVersions.apply)

  given Decoder[Instance] = Decoder.instance { cursor =>
    for
      domain        <- cursor.get[String]("domain")
      title         <- cursor.get[String]("title")
      version       <- cursor.get[String]("version")
      sourceUrl     <- cursor.get[String]("source_url")
      description   <- cursor.get[String]("description")
      apiVersions   <- cursor.get[ApiVersions]("api_versions")
      registrations <- cursor.get[Registrations]("registrations")
      configuration <- cursor.get[InstanceConfiguration]("configuration")
      contactEmail <- cursor.get[Option[Json]]("contact").flatMap {
        case Some(json) => json.hcursor.get[Option[String]]("email")
        case None       => Right(None)
      }
    yield Instance(
      domain = domain,
      title = title,
      version = version,
      sourceUrl = sourceUrl,
      description = description,
      apiVersions = apiVersions,
      registrations = registrations,
      configuration = configuration,
      contactEmail = contactEmail
    )
  }
