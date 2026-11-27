# Models — `ca.schwitzer.scaladon.models`

Wire-shaped case classes and the play-json `Reads` that decode Mastodon's JSON into them. They are **read models**: the client decodes responses into these types and only two of them can be written back (`AppCredentials` in the parent package, `AccountUpdateData` here).

Rules for editing live in [AGENTS.md](AGENTS.md). Client mechanics live in [../AGENTS.md](../AGENTS.md). **Frozen snapshot — not compiled**: [legacy/README.md](../../../../../../../README.md). Project overview: [root README](../../../../../../../../README.md).

> Field types (notably `Int` ids), enum decoding and `URL` capitalisation are legacy decisions the new implementation deliberately reverses. This page documents the baseline the port is measured against.

## Catalogue

Scala field names are camelCase; the JSON key is shown only where it differs. All `Reads` are `implicit` in the companion and use play-json functional syntax.

| Model | File | Fields (JSON key when different) | Represents |
|---|---|---|---|
| `Account` | `Account.scala` | `id`, `username`, `acct`, `displayName` (`display_name`), `note`, `url`, `avatar`, `header`, `locked`, `createdAt` (`created_at`), `followersCount`, `followingCount`, `statusesCount` | a user profile |
| `AccountUpdateData` | `Account.scala` | `displayName`, `note`, `avatar`, `header` — all `Option`; `Writes` only, empty options omitted | profile edit payload; **no client method uses it** |
| `Application` | `Application.scala` | `name`, `website: Option[String]` | the client app that posted a status |
| `Attachment` | `Attachment.scala` | `id`, `attachmentType` (`type`), `URL` (`url`), `remoteURL` (`remote_url`), `previewURL` (`preview_url`), `textURL: Option` (`text_url`) | media on a status |
| `Card` | `Card.scala` | `title`, `description`, `URL` (`url`), `image` | link preview |
| `Context` | `Context.scala` | `ancestors`, `descendants: Seq[Status]` | conversation around a status |
| `Error` | `Error.scala` | `error` | server error body; consumed by `handleAs` |
| `Instance` | `Instance.scala` | `title`, `description`, `email`, `URI` (`uri`) | server metadata |
| `Mention` | `Mention.scala` | `accountId` (`id`), `username`, `acct`, `URL` (`url`) | an @mention inside a status |
| `Notification` | `Notification.scala` | `id`, `notificationType` (`type`), `createdAt` (`created_at`), `account`, `status: Option[Status]` | one event in the notification feed |
| `Relationship` | `Relationship.scala` | `id`, `following`, `followedBy` (`followed_by`), `blocking`, `muting`, `requested` | follow/block/mute state toward an account |
| `Report` | `Report.scala` | `id`, `actionTaken` (`action_taken`) | a moderation report |
| `Results` | `Results.scala` | `accounts: Seq[Account]`, `statuses: Seq[Status]`, `hashtags: Seq[String]` | `/api/v1/search` payload |
| `Status` | `Status.scala` | 20 fields — see below | a post ("toot") |
| `Tag` | `Tag.scala` | `name`, `URL` (`url`) | a hashtag on a status |

### `Status`

`id`, `uri`, `url`, `account: Account`, `inReplyToId: Option[Int]` (`in_reply_to_id`), `inReplyToAccountId: Option[Int]` (`in_reply_to_account_id`), `reblog: Option[Status]`, `content`, `createdAt` (`created_at`), `reblogsCount` (`reblogs_count`), `favouritesCount` (`favourites_count`), `reblogged`, `favourited`, `sensitive`, `spoilerText: Option[String]` (`spoiler_text`), `visibility: StatusVisibility`, `mediaAttachments: Seq[Attachment]` (`media_attachments`), `mentions: Seq[Mention]`, `tags: Seq[Tag]`, `application: Option[Application]`.

`reblogged`, `favourited` and `sensitive` default to `false` when absent (`readWithDefault`); `spoiler_text` is decoded as a `String` and mapped `"" -> None`; `reblog` is recursive and uses `lazyReadNullable[Status](reads)`.

## Enums

Each enum is a `sealed trait` plus a companion `Reads` and a plural `Types` object of `final case object`s. `toString` yields the wire value.

| Trait | Values | Wire strings |
|---|---|---|
| `AttachmentType` | `Image`, `Video`, `Gifv` | `image`, `video`, `gifv` |
| `NotificationType` | `Mention`, `Reblog`, `Favourite`, `Follow` | `mention`, `reblog`, `favourite`, `follow` |
| `StatusVisibility` | `Default`, `Direct`, `Private`, `Public`, `Unlisted` | `""`, `direct`, `private`, `public`, `unlisted` |

## Graph

```
Status ──┬─ account: Account
         ├─ reblog: Status (recursive)
         ├─ mediaAttachments: Attachment -> AttachmentType
         ├─ mentions: Mention
         ├─ tags: Tag
         └─ application: Application
Notification ── account: Account, status: Option[Status]
Context      ── ancestors/descendants: Status
Results      ── accounts: Account, statuses: Status, hashtags: Seq[String]
```

`Account`, `Relationship`, `Report`, `Instance`, `Card`, `Error` and `AccountUpdateData` are leaves.

## Known issues in this layer

Line-level detail is in the [defect ledger](../../../../../../../README.md#defect-ledger).

* **Ids are `Int`** (#4). Mastodon ids are decimal strings; values above `Int.MaxValue` fail to decode. Every model is affected.
* **Enum reads are partial** (#9): an unknown `type` or `visibility` throws `MatchError` during decoding instead of returning a `JsError`.
* **`created_at` format** (#5): `dateReads` expects a numeric offset (`+0000`), not the `Z` Mastodon sends.
* **Capitalised field names** (`URL`, `URI`) are legacy API surface; renaming them is a source-breaking change for pattern matches.
* **`Results.hashtags` is `Seq[String]`**; later Mastodon versions return tag objects. It reflects the API as of 2017.
* **`AccountUpdateData` is dead code** in the current client — no endpoint sends it.
* `Card` names its decoder `read`, every other model uses `reads`; do not rely on the name when importing explicitly.
