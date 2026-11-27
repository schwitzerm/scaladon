package ca.schwitzer.scaladon.codec

import cats.syntax.traverse.*
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}

import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}
import scala.util.Try

import ca.schwitzer.scaladon.domain.{
  Account,
  AccountField,
  AccountRole,
  AccountSource,
  AccountState,
  AccountStateError,
  AccountsConfiguration,
  Announcement,
  AnnouncementMention,
  AnnouncementReaction,
  AnnouncementStatus,
  ApiVersions,
  Collection,
  CollectionItem,
  CollectionItemState,
  CollectionWithAccounts,
  CollectionsConfiguration,
  Context,
  CredentialAccount,
  CustomEmoji,
  FeedAccess,
  Id,
  Instance,
  InstanceConfiguration,
  InstanceUrls,
  MediaAttachment,
  MediaConfiguration,
  MediaType,
  Notification,
  NotificationFallback,
  OAuthServerMetadata,
  OAuthToken,
  Poll,
  PollOption,
  PollsConfiguration,
  PreviewCard,
  PreviewCardType,
  Profile,
  ProfileField,
  Quote,
  QuoteApprovalPolicy,
  QuoteState,
  Registrations,
  Relationship,
  ScheduledStatus,
  ScheduledStatusParams,
  ScheduledStatusPoll,
  ShallowQuote,
  ShallowTag,
  Status,
  StatusPostResult,
  StatusesConfiguration,
  Tag,
  TagHistory,
  TimelineAccess,
  TimelinesAccess,
  TranslationConfiguration,
  TrendLink,
  UserInfo,
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

  given Decoder[Account] = accountDecoder

  /** Recursive because `moved` embeds another account. Optional fields that Mastodon omits in some
    * states default only when the wire type is a plain boolean/integer; tri-state flags stay
    * `Option`. `last_status_at` is a date (`2019-11-17`) on the wire since Mastodon 3.1, so it goes
    * through the date-or-datetime parser rather than the strict `Instant` decoder.
    */
  private lazy val accountDecoder: Decoder[Account] =
    Decoder.instance { cursor =>
      for
        id                <- cursor.get[Id]("id")
        username          <- cursor.get[String]("username")
        acct              <- cursor.get[String]("acct")
        displayName       <- cursor.get[String]("display_name")
        note              <- cursor.get[String]("note")
        url               <- cursor.get[Option[String]]("url").map(_.filter(_.nonEmpty))
        uri               <- cursor.get[Option[String]]("uri").map(_.filter(_.nonEmpty))
        avatar            <- cursor.get[String]("avatar")
        avatarStatic      <- cursor.get[Option[String]]("avatar_static").map(_.filter(_.nonEmpty))
        avatarDescription <- cursor.get[Option[String]]("avatar_description").map(_.filter(_.nonEmpty))
        header            <- cursor.get[String]("header")
        headerStatic      <- cursor.get[Option[String]]("header_static").map(_.filter(_.nonEmpty))
        headerDescription <- cursor.get[Option[String]]("header_description").map(_.filter(_.nonEmpty))
        locked            <- flag(cursor, "locked")
        bot               <- flag(cursor, "bot")
        group             <- flag(cursor, "group")
        discoverable      <- cursor.get[Option[Boolean]]("discoverable")
        indexable         <- flag(cursor, "indexable")
        noindex           <- cursor.get[Option[Boolean]]("noindex")
        moved             <- optionalAccount(cursor, "moved")
        memorial          <- cursor.get[Option[Boolean]]("memorial")
        suspended         <- cursor.get[Option[Boolean]]("suspended")
        limited           <- cursor.get[Option[Boolean]]("limited")
        createdAt         <- requiredTimestamp(cursor, "created_at")
        lastStatusAt      <- optionalTimestamp(cursor, "last_status_at")
        statusesCount     <- count(cursor, "statuses_count")
        followersCount    <- count(cursor, "followers_count")
        followingCount    <- count(cursor, "following_count")
        hideCollections   <- cursor.get[Option[Boolean]]("hide_collections")
        showMedia         <- flag(cursor, "show_media")
        showMediaReplies  <- flag(cursor, "show_media_replies")
        showFeatured      <- flag(cursor, "show_featured")
        fields            <- cursor.get[Option[List[AccountField]]]("fields").map(_.getOrElse(Nil))
        invalidHandle     <- cursor.get[Option[Boolean]]("invalid_handle")
      yield Account(
        id = id,
        username = username,
        acct = acct,
        displayName = displayName,
        note = note,
        url = url,
        uri = uri,
        avatar = avatar,
        avatarStatic = avatarStatic,
        avatarDescription = avatarDescription,
        header = header,
        headerStatic = headerStatic,
        headerDescription = headerDescription,
        locked = locked,
        bot = bot,
        group = group,
        discoverable = discoverable,
        indexable = indexable,
        noindex = noindex,
        moved = moved,
        memorial = memorial,
        suspended = suspended,
        limited = limited,
        createdAt = createdAt,
        lastStatusAt = lastStatusAt,
        statusesCount = statusesCount,
        followersCount = followersCount,
        followingCount = followingCount,
        hideCollections = hideCollections,
        showMedia = showMedia,
        showMediaReplies = showMediaReplies,
        showFeatured = showFeatured,
        fields = fields,
        invalidHandle = invalidHandle
      )
    }

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

  // --- accounts codecs ---

  private def flag(cursor: HCursor, field: String): Decoder.Result[Boolean] =
    cursor.get[Option[Boolean]](field).map(_.getOrElse(false))

  private def count(cursor: HCursor, field: String): Decoder.Result[Int] =
    cursor.get[Option[Int]](field).map(_.getOrElse(0))

  /** Parses the three timestamp shapes Mastodon emits: RFC 3339 with `Z`, with a numeric offset
    * (`+00:00` on verified profile fields), and a bare date (`last_status_at`, since 3.1), which is
    * midnight UTC. The strict `Decoder[Instant]` stays as it was for every other field.
    */
  private def parseTimestamp(value: String): Either[String, Instant] =
    Try(Instant.parse(value))
      .orElse(Try(OffsetDateTime.parse(value).toInstant))
      .orElse(Try(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant))
      .toEither
      .left
      .map(error => s"Invalid timestamp '$value': ${error.getMessage}")

  private def requiredTimestamp(cursor: HCursor, field: String): Decoder.Result[Instant] =
    cursor.get[String](field).flatMap { value =>
      parseTimestamp(value).left.map(message => DecodingFailure(message, cursor.history))
    }

  private def optionalTimestamp(cursor: HCursor, field: String): Decoder.Result[Option[Instant]] =
    cursor.get[Option[String]](field).flatMap {
      case Some(value) if value.nonEmpty =>
        parseTimestamp(value).left.map(message => DecodingFailure(message, cursor.history)).map(Some(_))
      case _ => Right(None)
    }

  private def optionalAccount(cursor: HCursor, field: String): Decoder.Result[Option[Account]] =
    cursor.get[Option[Json]](field).flatMap(_.traverse(accountDecoder.decodeJson))

  given Decoder[AccountField] = Decoder.instance { cursor =>
    for
      name       <- cursor.get[String]("name")
      value      <- cursor.get[String]("value")
      verifiedAt <- optionalTimestamp(cursor, "verified_at")
    yield AccountField(name, value, verifiedAt)
  }

  given Decoder[AccountRole] = Decoder.instance { cursor =>
    for
      id    <- cursor.get[Id]("id")
      name  <- cursor.get[String]("name")
      color <- cursor.get[Option[String]]("color").map(_.getOrElse(""))
    yield AccountRole(id, name, color)
  }

  given Decoder[AccountSource] = Decoder.instance { cursor =>
    for
      privacy            <- cursor.get[Visibility]("privacy")
      sensitive          <- flag(cursor, "sensitive")
      spoilerField       <- flag(cursor, "spoiler_field")
      language           <- cursor.get[Option[String]]("language").map(_.filter(_.nonEmpty))
      note               <- cursor.get[String]("note")
      fields             <- cursor.get[Option[List[AccountField]]]("fields").map(_.getOrElse(Nil))
      followRequests     <- count(cursor, "follow_requests_count")
      hideCollections    <- cursor.get[Option[Boolean]]("hide_collections")
      discoverable       <- cursor.get[Option[Boolean]]("discoverable")
      indexable          <- flag(cursor, "indexable")
      attributionDomains <- cursor.get[Option[List[String]]]("attribution_domains").map(_.getOrElse(Nil))
      quotePolicy        <- cursor.get[Option[String]]("quote_policy")
    yield AccountSource(
      privacy = privacy,
      sensitive = sensitive,
      spoilerField = spoilerField,
      language = language,
      note = note,
      fields = fields,
      followRequestsCount = followRequests,
      hideCollections = hideCollections,
      discoverable = discoverable,
      indexable = indexable,
      attributionDomains = attributionDomains,
      quotePolicy = quotePolicy
    )
  }

  given Decoder[AccountStateError] = Decoder.decodeString.emap(value =>
    AccountStateError.fromWire(value).toRight(s"Unknown account state error code '$value'")
  )

  given Decoder[AccountState] = Decoder.instance { cursor =>
    for
      functional <- flag(cursor, "functional")
      confirmed  <- flag(cursor, "confirmed")
      approved   <- flag(cursor, "approved")
      error      <- cursor.get[Option[String]]("error").map(_.filter(_.nonEmpty))
      errorCode  <- cursor.get[Option[AccountStateError]]("error_code")
    yield AccountState(functional, confirmed, approved, error, errorCode)
  }

  given Decoder[CredentialAccount] = Decoder.instance { cursor =>
    for
      account <- accountDecoder(cursor)
      source  <- cursor.get[AccountSource]("source")
      role    <- cursor.get[Option[AccountRole]]("role")
      state   <- cursor.get[Option[AccountState]]("state")
    yield CredentialAccount(account, source, role, state)
  }

  given Decoder[Relationship] = Decoder.instance { cursor =>
    for
      id                  <- cursor.get[Id]("id")
      following           <- flag(cursor, "following")
      showingReblogs      <- flag(cursor, "showing_reblogs")
      notifying           <- flag(cursor, "notifying")
      languages           <- cursor.get[Option[List[String]]]("languages")
      followedBy          <- flag(cursor, "followed_by")
      blocking            <- flag(cursor, "blocking")
      blockedBy           <- flag(cursor, "blocked_by")
      muting              <- flag(cursor, "muting")
      mutingNotifications <- flag(cursor, "muting_notifications")
      mutingExpiresAt     <- optionalTimestamp(cursor, "muting_expires_at")
      requested           <- flag(cursor, "requested")
      requestedBy         <- flag(cursor, "requested_by")
      domainBlocking      <- flag(cursor, "domain_blocking")
      endorsed            <- flag(cursor, "endorsed")
      note                <- cursor.get[Option[String]]("note").map(_.getOrElse(""))
    yield Relationship(
      id = id,
      following = following,
      showingReblogs = showingReblogs,
      notifying = notifying,
      languages = languages,
      followedBy = followedBy,
      blocking = blocking,
      blockedBy = blockedBy,
      muting = muting,
      mutingNotifications = mutingNotifications,
      mutingExpiresAt = mutingExpiresAt,
      requested = requested,
      requestedBy = requestedBy,
      domainBlocking = domainBlocking,
      endorsed = endorsed,
      note = note
    )
  }

  given Decoder[ProfileField] = Decoder.instance { cursor =>
    for
      name       <- cursor.get[String]("name")
      value      <- cursor.get[String]("value")
      verifiedAt <- optionalTimestamp(cursor, "verified_at")
    yield ProfileField(name, value, verifiedAt)
  }

  given Decoder[Profile] = Decoder.instance { cursor =>
    for
      id                 <- cursor.get[Id]("id")
      displayName        <- cursor.get[String]("display_name")
      note               <- cursor.get[String]("note")
      fields             <- cursor.get[Option[List[ProfileField]]]("fields").map(_.getOrElse(Nil))
      avatar             <- cursor.get[Option[String]]("avatar").map(_.filter(_.nonEmpty))
      avatarStatic       <- cursor.get[Option[String]]("avatar_static").map(_.filter(_.nonEmpty))
      avatarDescription  <- cursor.get[Option[String]]("avatar_description").map(_.getOrElse(""))
      header             <- cursor.get[Option[String]]("header").map(_.filter(_.nonEmpty))
      headerStatic       <- cursor.get[Option[String]]("header_static").map(_.filter(_.nonEmpty))
      headerDescription  <- cursor.get[Option[String]]("header_description").map(_.getOrElse(""))
      locked             <- flag(cursor, "locked")
      bot                <- flag(cursor, "bot")
      hideCollections    <- cursor.get[Option[Boolean]]("hide_collections")
      discoverable       <- cursor.get[Option[Boolean]]("discoverable")
      indexable          <- flag(cursor, "indexable")
      showMedia          <- flag(cursor, "show_media")
      showMediaReplies   <- flag(cursor, "show_media_replies")
      showFeatured       <- flag(cursor, "show_featured")
      attributionDomains <- cursor.get[Option[List[String]]]("attribution_domains").map(_.getOrElse(Nil))
      formattedNote      <- cursor.get[Option[String]]("formatted_note").map(_.filter(_.nonEmpty))
      formattedFields    <- cursor.get[Option[List[ProfileField]]]("formatted_fields").map(_.getOrElse(Nil))
    yield Profile(
      id = id,
      displayName = displayName,
      note = note,
      fields = fields,
      avatar = avatar,
      avatarStatic = avatarStatic,
      avatarDescription = avatarDescription,
      header = header,
      headerStatic = headerStatic,
      headerDescription = headerDescription,
      locked = locked,
      bot = bot,
      hideCollections = hideCollections,
      discoverable = discoverable,
      indexable = indexable,
      showMedia = showMedia,
      showMediaReplies = showMediaReplies,
      showFeatured = showFeatured,
      attributionDomains = attributionDomains,
      formattedNote = formattedNote,
      formattedFields = formattedFields
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

  // --- collections/notifications codecs ---

  given Decoder[ShallowTag] = Decoder.forProduct2("name", "url")(ShallowTag.apply)

  given Decoder[CollectionItemState] = Decoder.decodeString.emap(value =>
    CollectionItemState.fromWire(value).toRight(s"Unknown collection item state '$value'")
  )

  given Decoder[CollectionItem] = Decoder.instance { cursor =>
    for
      id        <- cursor.get[Id]("id")
      accountId <- cursor.get[Option[Id]]("account_id")
      state     <- cursor.get[CollectionItemState]("state")
      createdAt <- cursor.get[Instant]("created_at")
    yield CollectionItem(id, accountId, state, createdAt)
  }

  given Decoder[Collection] = Decoder.instance { cursor =>
    for
      id           <- cursor.get[Id]("id")
      accountId    <- cursor.get[Id]("account_id")
      uri          <- cursor.get[String]("uri")
      url          <- cursor.get[Option[String]]("url").map(_.filter(_.nonEmpty))
      name         <- cursor.get[String]("name")
      description  <- cursor.get[Option[String]]("description").map(_.filter(_.nonEmpty))
      language     <- cursor.get[Option[String]]("language")
      local        <- cursor.get[Boolean]("local")
      sensitive    <- cursor.get[Boolean]("sensitive")
      discoverable <- cursor.get[Boolean]("discoverable")
      tag          <- cursor.get[Option[ShallowTag]]("tag")
      itemCount    <- cursor.get[Int]("item_count")
      items        <- cursor.get[List[CollectionItem]]("items")
      createdAt    <- cursor.get[Instant]("created_at")
      updatedAt    <- cursor.get[Instant]("updated_at")
    yield Collection(
      id = id,
      accountId = accountId,
      uri = uri,
      url = url,
      name = name,
      description = description,
      language = language,
      local = local,
      sensitive = sensitive,
      discoverable = discoverable,
      tag = tag,
      itemCount = itemCount,
      items = items,
      createdAt = createdAt,
      updatedAt = updatedAt
    )
  }

  given Decoder[CollectionWithAccounts] = Decoder.instance { cursor =>
    for
      accounts   <- cursor.get[List[Account]]("accounts")
      collection <- cursor.get[Collection]("collection")
    yield CollectionWithAccounts(accounts, collection)
  }

  given Decoder[NotificationFallback] = Decoder.instance { cursor =>
    for
      title   <- cursor.get[String]("title")
      summary <- cursor.get[Option[String]]("summary")
      details <- cursor.get[Option[String]]("details")
    yield NotificationFallback(title, summary, details)
  }

  given Decoder[Notification] = Decoder.instance { cursor =>
    for
      id         <- cursor.get[Id]("id")
      kind       <- cursor.get[String]("type")
      groupKey   <- cursor.get[Option[String]]("group_key")
      createdAt  <- cursor.get[Instant]("created_at")
      account    <- cursor.get[Account]("account")
      status     <- cursor.get[Option[Json]]("status").flatMap(decodeOption)
      collection <- cursor.get[Option[Collection]]("collection")
      fallback   <- cursor.get[Option[NotificationFallback]]("fallback")
    yield Notification(
      id = id,
      kind = kind,
      groupKey = groupKey,
      createdAt = createdAt,
      account = account,
      status = status,
      collection = collection,
      fallback = fallback
    )
  }
  // --- oauth codecs ---

  /** The Token entity's `created_at` is a UNIX timestamp in seconds, unlike every other timestamp
    * in the API. An RFC 3339 string is also accepted so a proxy or a future server revision does
    * not break token exchange.
    */
  private val tokenCreatedAt: Decoder[Instant] =
    Decoder.instance { cursor =>
      cursor.value.asNumber
        .flatMap(_.toLong)
        .map(Instant.ofEpochSecond)
        .orElse(cursor.value.asString.flatMap(value => Try(Instant.parse(value)).toOption))
        .toRight(
          DecodingFailure(
            "created_at must be a UNIX timestamp in seconds or an RFC 3339 string",
            cursor.history
          )
        )
    }

  given Decoder[OAuthToken] = Decoder.instance { cursor =>
    for
      accessToken <- cursor.get[String]("access_token")
      tokenType   <- cursor.get[String]("token_type")
      scope       <- cursor.get[String]("scope").map(_.split(' ').toList.filter(_.nonEmpty))
      createdAt   <- cursor.get[Json]("created_at").flatMap(tokenCreatedAt.decodeJson)
      expiresIn   <- cursor.get[Option[Long]]("expires_in")
    yield OAuthToken(accessToken, tokenType, scope, createdAt, expiresIn)
  }

  given Decoder[OAuthServerMetadata] = Decoder.instance { cursor =>
    def strings(field: String): Decoder.Result[List[String]] =
      cursor.get[Option[List[String]]](field).map(_.getOrElse(Nil))

    for
      issuer               <- cursor.get[String]("issuer")
      documentation        <- cursor.get[Option[String]]("service_documentation")
      authorization        <- cursor.get[String]("authorization_endpoint")
      token                <- cursor.get[String]("token_endpoint")
      appRegistration      <- cursor.get[Option[String]]("app_registration_endpoint")
      revocation           <- cursor.get[Option[String]]("revocation_endpoint")
      userinfo             <- cursor.get[Option[String]]("userinfo_endpoint")
      scopes               <- strings("scopes_supported")
      responseTypes        <- strings("response_types_supported")
      responseModes        <- strings("response_modes_supported")
      grantTypes           <- strings("grant_types_supported")
      codeChallengeMethods <- strings("code_challenge_methods_supported")
      authMethods          <- strings("token_endpoint_auth_methods_supported")
      promptValues         <- strings("prompt_values_supported")
    yield OAuthServerMetadata(
      issuer = issuer,
      serviceDocumentation = documentation,
      authorizationEndpoint = authorization,
      tokenEndpoint = token,
      appRegistrationEndpoint = appRegistration,
      revocationEndpoint = revocation,
      userinfoEndpoint = userinfo,
      scopesSupported = scopes,
      responseTypesSupported = responseTypes,
      responseModesSupported = responseModes,
      grantTypesSupported = grantTypes,
      codeChallengeMethodsSupported = codeChallengeMethods,
      tokenEndpointAuthMethodsSupported = authMethods,
      promptValuesSupported = promptValues
    )
  }

  given Decoder[UserInfo] = Decoder.forProduct6(
    "iss",
    "sub",
    "name",
    "preferred_username",
    "profile",
    "picture"
  )(UserInfo.apply)

  // --- shared entities codecs ---

  /** Tag history counts are integers in the docs but numeric strings on real payloads. */
  private val intLike: Decoder[Int] = Decoder.decodeJson.emap { json =>
    json.asNumber
      .flatMap(_.toInt)
      .orElse(json.asString.flatMap(_.toIntOption))
      .toRight("expected an integer or a numeric string")
  }

  given Decoder[TagHistory] = Decoder.instance { cursor =>
    for
      day      <- cursor.get[String]("day")
      uses     <- cursor.get[Int]("uses")(using intLike)
      accounts <- cursor.get[Int]("accounts")(using intLike)
    yield TagHistory(day, uses, accounts)
  }

  given Decoder[Tag] = Decoder.instance { cursor =>
    for
      name      <- cursor.get[String]("name")
      url       <- cursor.get[String]("url")
      history   <- cursor.get[Option[List[TagHistory]]]("history").map(_.getOrElse(Nil))
      following <- cursor.get[Option[Boolean]]("following")
    yield Tag(name, url, history, following)
  }

  given Decoder[MediaType] = Decoder.decodeString.emap(value =>
    MediaType.fromWire(value).toRight(s"Unknown media type '$value'")
  )

  given Decoder[MediaAttachment] = Decoder.instance { cursor =>
    for
      id          <- cursor.get[Id]("id")
      kind        <- cursor.get[MediaType]("type")
      url         <- cursor.get[Option[String]]("url").map(_.filter(_.nonEmpty))
      previewUrl  <- cursor.get[Option[String]]("preview_url").map(_.filter(_.nonEmpty))
      remoteUrl   <- cursor.get[Option[String]]("remote_url").map(_.filter(_.nonEmpty))
      textUrl     <- cursor.get[Option[String]]("text_url").map(_.filter(_.nonEmpty))
      description <- cursor.get[Option[String]]("description").map(_.filter(_.nonEmpty))
      blurhash    <- cursor.get[Option[String]]("blurhash").map(_.filter(_.nonEmpty))
    yield MediaAttachment(id, kind, url, previewUrl, remoteUrl, textUrl, description, blurhash)
  }

  given Decoder[PollOption] =
    Decoder.forProduct2("title", "votes_count")(PollOption.apply)

  given Decoder[Poll] = Decoder.instance { cursor =>
    for
      id          <- cursor.get[Id]("id")
      expiresAt   <- cursor.get[Option[Instant]]("expires_at")
      expired     <- cursor.get[Boolean]("expired")
      multiple    <- cursor.get[Boolean]("multiple")
      votesCount  <- cursor.get[Int]("votes_count")
      votersCount <- cursor.get[Option[Int]]("voters_count")
      voted       <- cursor.get[Option[Boolean]]("voted")
      ownVotes    <- cursor.get[Option[List[Int]]]("own_votes")
      options     <- cursor.get[List[PollOption]]("options")
    yield Poll(id, expiresAt, expired, multiple, votesCount, votersCount, voted, ownVotes, options)
  }

  // --- announcements and trends codecs ---

  given Decoder[CustomEmoji] = Decoder.instance { cursor =>
    for
      shortcode       <- cursor.get[String]("shortcode")
      url             <- cursor.get[String]("url")
      staticUrl       <- cursor.get[String]("static_url")
      visibleInPicker <- cursor.get[Option[Boolean]]("visible_in_picker").map(_.getOrElse(false))
      category        <- cursor.get[Option[String]]("category").map(_.filter(_.nonEmpty))
    yield CustomEmoji(shortcode, url, staticUrl, visibleInPicker, category)
  }

  given Decoder[AnnouncementMention] =
    Decoder.forProduct4("id", "username", "url", "acct")(AnnouncementMention.apply)

  given Decoder[AnnouncementStatus] =
    Decoder.forProduct2("id", "url")(AnnouncementStatus.apply)

  given Decoder[AnnouncementReaction] = Decoder.instance { cursor =>
    for
      name      <- cursor.get[String]("name")
      count     <- cursor.get[Int]("count")
      me        <- cursor.get[Option[Boolean]]("me").map(_.getOrElse(false))
      url       <- cursor.get[Option[String]]("url").map(_.filter(_.nonEmpty))
      staticUrl <- cursor.get[Option[String]]("static_url").map(_.filter(_.nonEmpty))
    yield AnnouncementReaction(name, count, me, url, staticUrl)
  }

  given Decoder[Announcement] = Decoder.instance { cursor =>
    for
      id               <- cursor.get[Id]("id")
      content          <- cursor.get[String]("content")
      startsAt         <- cursor.get[Option[Instant]]("starts_at")
      endsAt           <- cursor.get[Option[Instant]]("ends_at")
      allDay           <- cursor.get[Option[Boolean]]("all_day").map(_.getOrElse(false))
      publishedAt      <- cursor.get[Instant]("published_at")
      updatedAt        <- cursor.get[Instant]("updated_at")
      read             <- cursor.get[Option[Boolean]]("read")
      mentions         <- cursor.get[Option[List[AnnouncementMention]]]("mentions").map(_.getOrElse(Nil))
      statuses         <- cursor.get[Option[List[AnnouncementStatus]]]("statuses").map(_.getOrElse(Nil))
      tags             <- cursor.get[Option[List[ShallowTag]]]("tags").map(_.getOrElse(Nil))
      emojis           <- cursor.get[Option[List[CustomEmoji]]]("emojis").map(_.getOrElse(Nil))
      reactions        <- cursor.get[Option[List[AnnouncementReaction]]]("reactions").map(_.getOrElse(Nil))
      reactionsAllowed <- cursor.get[Option[Boolean]]("reactions_allowed").map(_.getOrElse(false))
    yield Announcement(
      id = id,
      content = content,
      startsAt = startsAt,
      endsAt = endsAt,
      allDay = allDay,
      publishedAt = publishedAt,
      updatedAt = updatedAt,
      read = read,
      mentions = mentions,
      statuses = statuses,
      tags = tags,
      emojis = emojis,
      reactions = reactions,
      reactionsAllowed = reactionsAllowed
    )
  }

  given Decoder[TrendLink] = Decoder.instance { cursor =>
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
      history      <- cursor.get[Option[List[TagHistory]]]("history").map(_.getOrElse(Nil))
    yield TrendLink(
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
      history = history
    )
  }
