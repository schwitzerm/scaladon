package ca.schwitzer.scaladon.domain

/** Access restriction on one timeline feed, as reported by `GET /api/v2/instance`. */
enum TimelineAccess:
  case Public
  case Authenticated
  case Disabled

object TimelineAccess:
  def fromWire(value: String): Option[TimelineAccess] = value match
    case "public"        => Some(Public)
    case "authenticated" => Some(Authenticated)
    case "disabled"      => Some(Disabled)
    case _               => None

/** Local and remote access restrictions for one feed family. */
final case class FeedAccess(local: TimelineAccess, remote: TimelineAccess)

/** `configuration.timelines_access` (Mastodon 4.5+). */
final case class TimelinesAccess(
    liveFeeds: FeedAccess,
    hashtagFeeds: FeedAccess,
    trendingLinkFeeds: FeedAccess
)

/** `configuration.urls`. */
final case class InstanceUrls(
    streaming: String,
    status: Option[String],
    about: String,
    privacyPolicy: Option[String],
    termsOfService: Option[String]
)

/** `configuration.vapid`; the key clients use for Web Push subscriptions. */
final case class VapidConfiguration(publicKey: String)

/** `configuration.accounts` (Mastodon 4.6+ limits included). */
final case class AccountsConfiguration(
    maxDisplayNameLength: Int,
    maxNoteLength: Int,
    maxAvatarDescriptionLength: Int,
    maxHeaderDescriptionLength: Int,
    maxFeaturedTags: Int,
    maxPinnedStatuses: Int,
    maxProfileFields: Int,
    profileFieldNameLimit: Int,
    profileFieldValueLimit: Int
)

/** `configuration.statuses`. */
final case class StatusesConfiguration(
    maxCharacters: Int,
    maxMediaAttachments: Int,
    charactersReservedPerUrl: Int
)

/** `configuration.media_attachments`. */
final case class MediaConfiguration(
    descriptionLimit: Int,
    supportedMimeTypes: List[String],
    imageSizeLimit: Int,
    imageMatrixLimit: Int,
    videoSizeLimit: Int,
    videoMatrixLimit: Int,
    videoFrameRateLimit: Int
)

/** `configuration.polls`. */
final case class PollsConfiguration(
    maxOptions: Int,
    maxCharactersPerOption: Int,
    minExpiration: Int,
    maxExpiration: Int
)

/** `configuration.translation`. */
final case class TranslationConfiguration(enabled: Boolean)

/** `configuration.collections` (Mastodon 5.0+). */
final case class CollectionsConfiguration(
    maxItems: Int,
    maxNameLength: Int,
    maxDescriptionLength: Int
)

/** `configuration`, flattened to the values a client actually branches on. */
final case class InstanceConfiguration(
    urls: InstanceUrls,
    vapid: VapidConfiguration,
    accounts: AccountsConfiguration,
    statuses: StatusesConfiguration,
    mediaAttachments: MediaConfiguration,
    polls: PollsConfiguration,
    translation: TranslationConfiguration,
    timelinesAccess: TimelinesAccess,
    limitedFederation: Boolean,
    collections: CollectionsConfiguration
)

/** `registrations`, including the 5.0 `oauth` flag for the `prompt=create` flow. */
final case class Registrations(
    enabled: Boolean,
    approvalRequired: Boolean,
    reasonRequired: Boolean,
    minAge: Option[Int],
    message: Option[String],
    url: Option[String],
    oauth: Boolean
)

/** `api_versions`: the reliable capability gate; `mastodon` is 12 for Mastodon 5.0. */
final case class ApiVersions(mastodon: Int)

/** The `GET /api/v2/instance` response: the version gate for every other namespace. */
final case class Instance(
    domain: String,
    title: String,
    version: String,
    sourceUrl: String,
    description: String,
    apiVersions: ApiVersions,
    registrations: Registrations,
    configuration: InstanceConfiguration,
    contactEmail: Option[String]
)
