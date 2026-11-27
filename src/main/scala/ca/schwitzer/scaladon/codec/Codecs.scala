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
  ApiVersions,
  CollectionsConfiguration,
  CredentialAccount,
  FeedAccess,
  Id,
  Instance,
  InstanceConfiguration,
  InstanceUrls,
  MediaConfiguration,
  PollsConfiguration,
  Profile,
  ProfileField,
  Registrations,
  Relationship,
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
