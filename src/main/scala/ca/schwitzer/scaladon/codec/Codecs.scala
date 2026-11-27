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
  Context,
  FeedAccess,
  Id,
  Instance,
  InstanceConfiguration,
  InstanceUrls,
  MediaConfiguration,
  PollsConfiguration,
  PreviewCard,
  PreviewCardType,
  Quote,
  QuoteApprovalPolicy,
  QuoteState,
  Registrations,
  ScheduledStatus,
  ScheduledStatusParams,
  ScheduledStatusPoll,
  ShallowQuote,
  Status,
  StatusPostResult,
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

  /** Recursive because `reblog` and `quote` embed other statuses; a `lazy val` lets the decoder refer
    * to itself and to the quote decoder declared in the statuses block at the end of the object.
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
        repliesCount       <- cursor.get[Option[Int]]("replies_count").map(_.getOrElse(0))
        reblogsCount       <- cursor.get[Int]("reblogs_count")
        favouritesCount    <- cursor.get[Int]("favourites_count")
        quotesCount        <- cursor.get[Option[Int]]("quotes_count").map(_.getOrElse(0))
        favourited         <- cursor.get[Option[Boolean]]("favourited").map(_.getOrElse(false))
        reblogged          <- cursor.get[Option[Boolean]]("reblogged").map(_.getOrElse(false))
        bookmarked         <- cursor.get[Option[Boolean]]("bookmarked").map(_.getOrElse(false))
        muted              <- cursor.get[Option[Boolean]]("muted").map(_.getOrElse(false))
        pinned             <- cursor.get[Option[Boolean]]("pinned").map(_.getOrElse(false))
        editedAt           <- cursor.get[Option[Instant]]("edited_at")
        language           <- cursor.get[Option[String]]("language").map(_.filter(_.nonEmpty))
        text               <- cursor.get[Option[String]]("text").map(_.filter(_.nonEmpty))
        card               <- cursor.get[Option[PreviewCard]]("card")
        quote              <- cursor.get[Option[Quote]]("quote")
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
        repliesCount = repliesCount,
        reblogsCount = reblogsCount,
        favouritesCount = favouritesCount,
        quotesCount = quotesCount,
        favourited = favourited,
        reblogged = reblogged,
        bookmarked = bookmarked,
        muted = muted,
        pinned = pinned,
        editedAt = editedAt,
        language = language,
        text = text,
        card = card,
        quote = quote
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

  // --- statuses codecs ---

  given Decoder[QuoteState] = Decoder.decodeString.emap(value =>
    QuoteState.fromWire(value).toRight(s"Unknown quote state '$value'")
  )

  given Decoder[QuoteApprovalPolicy] = Decoder.decodeString.emap(value =>
    QuoteApprovalPolicy.fromWire(value).toRight(s"Unknown quote approval policy '$value'")
  )

  given Decoder[PreviewCardType] = Decoder.decodeString.emap(value =>
    PreviewCardType.fromWire(value).toRight(s"Unknown preview card type '$value'")
  )

  given Decoder[PreviewCard] = Decoder.instance { cursor =>
    for
      url          <- cursor.get[String]("url")
      title        <- cursor.get[String]("title")
      description  <- cursor.get[Option[String]]("description").map(_.getOrElse(""))
      cardType     <- cursor.get[PreviewCardType]("type")
      authorName   <- cursor.get[Option[String]]("author_name").map(_.getOrElse(""))
      authorUrl    <- cursor.get[Option[String]]("author_url").map(_.getOrElse(""))
      providerName <- cursor.get[Option[String]]("provider_name").map(_.getOrElse(""))
      providerUrl  <- cursor.get[Option[String]]("provider_url").map(_.getOrElse(""))
      html         <- cursor.get[Option[String]]("html").map(_.getOrElse(""))
      width        <- cursor.get[Option[Int]]("width").map(_.getOrElse(0))
      height       <- cursor.get[Option[Int]]("height").map(_.getOrElse(0))
      image        <- cursor.get[Option[String]]("image").map(_.filter(_.nonEmpty))
      embedUrl     <- cursor.get[Option[String]]("embed_url").map(_.getOrElse(""))
      blurhash     <- cursor.get[Option[String]]("blurhash").map(_.filter(_.nonEmpty))
      missingAttribution <- cursor
        .get[Option[Boolean]]("missing_attribution")
        .map(_.getOrElse(false))
    yield PreviewCard(
      url = url,
      title = title,
      description = description,
      cardType = cardType,
      authorName = authorName,
      authorUrl = authorUrl,
      providerName = providerName,
      providerUrl = providerUrl,
      html = html,
      width = width,
      height = height,
      image = image,
      embedUrl = embedUrl,
      blurhash = blurhash,
      missingAttribution = missingAttribution
    )
  }

  /** `quoted_status` embeds a `Status`, and `Status.quote` embeds a `Quote`; both decoders are
    * `lazy` so the cycle is broken while each stays a single shared instance.
    */
  given Decoder[Quote] = quote

  private lazy val quote: Decoder[Quote] =
    Decoder.instance { cursor =>
      for
        state          <- cursor.get[QuoteState]("state")
        quotedStatus   <- cursor.get[Option[Status]]("quoted_status")
        quotedStatusId <- cursor.get[Option[Id]]("quoted_status_id")
      yield Quote(state, quotedStatus, quotedStatusId)
    }

  given Decoder[ShallowQuote] = Decoder.instance { cursor =>
    for
      state          <- cursor.get[QuoteState]("state")
      quotedStatusId <- cursor.get[Option[Id]]("quoted_status_id")
    yield ShallowQuote(state, quotedStatusId)
  }

  given Decoder[Context] = Decoder.instance { cursor =>
    for
      ancestors   <- cursor.get[List[Status]]("ancestors")
      descendants <- cursor.get[List[Status]]("descendants")
    yield Context(ancestors, descendants)
  }

  given Decoder[ScheduledStatusPoll] = Decoder.instance { cursor =>
    for
      options    <- cursor.get[List[String]]("options")
      expiresIn  <- cursor.get[Int]("expires_in")
      multiple   <- cursor.get[Option[Boolean]]("multiple").map(_.getOrElse(false))
      hideTotals <- cursor.get[Option[Boolean]]("hide_totals").map(_.getOrElse(false))
    yield ScheduledStatusPoll(options, expiresIn, multiple, hideTotals)
  }

  given Decoder[ScheduledStatusParams] = Decoder.instance { cursor =>
    for
      text         <- cursor.get[Option[String]]("text").map(_.getOrElse(""))
      mediaIds     <- cursor.get[Option[List[Id]]]("media_ids")
      sensitive    <- cursor.get[Option[Boolean]]("sensitive")
      spoilerText  <- cursor.get[Option[String]]("spoiler_text").map(_.filter(_.nonEmpty))
      visibility   <- cursor.get[Option[Visibility]]("visibility")
      language     <- cursor.get[Option[String]]("language").map(_.filter(_.nonEmpty))
      scheduledAt  <- cursor.get[Option[Instant]]("scheduled_at")
      poll         <- cursor.get[Option[ScheduledStatusPoll]]("poll")
      idempotency  <- cursor.get[Option[String]]("idempotency")
      inReplyToId  <- cursor.get[Option[Id]]("in_reply_to_id")
      quotedStatusId <- cursor.get[Option[Id]]("quoted_status_id")
      policy       <- cursor.get[Option[QuoteApprovalPolicy]]("quote_approval_policy")
    yield ScheduledStatusParams(
      text = text,
      mediaIds = mediaIds,
      sensitive = sensitive,
      spoilerText = spoilerText,
      visibility = visibility,
      language = language,
      scheduledAt = scheduledAt,
      poll = poll,
      idempotency = idempotency,
      inReplyToId = inReplyToId,
      quotedStatusId = quotedStatusId,
      quoteApprovalPolicy = policy
    )
  }

  given Decoder[ScheduledStatus] = Decoder.instance { cursor =>
    for
      id          <- cursor.get[Id]("id")
      scheduledAt <- cursor.get[Instant]("scheduled_at")
      params      <- cursor.get[ScheduledStatusParams]("params")
    yield ScheduledStatus(id, scheduledAt, params)
  }

  /** `POST /api/v1/statuses` returns a `ScheduledStatus` exactly when `scheduled_at` is present. */
  given Decoder[StatusPostResult] = Decoder.instance { cursor =>
    val scheduled =
      cursor.value.asObject.exists(_.apply("scheduled_at").exists(json => !json.isNull))
    if scheduled then
      cursor.as[ScheduledStatus].map(StatusPostResult.Scheduled.apply)
    else cursor.as[Status].map(StatusPostResult.Posted.apply)
  }
