package ca.schwitzer.scaladon.domain

import java.time.Instant

/** The poll an author asked to attach when scheduling a status. */
final case class ScheduledStatusPoll(
    options: List[String],
    expiresIn: Int,
    multiple: Boolean,
    hideTotals: Boolean
)

/** The parameters captured when a status was scheduled.
  *
  * Every authoring option is optional on the wire because the server stores `null` for whatever the
  * caller did not supply. `applicationId` and `withRateLimit` are deprecated/ignored and not modelled.
  */
final case class ScheduledStatusParams(
    text: String,
    mediaIds: Option[List[Id]],
    sensitive: Option[Boolean],
    spoilerText: Option[String],
    visibility: Option[Visibility],
    language: Option[String],
    scheduledAt: Option[Instant],
    poll: Option[ScheduledStatusPoll],
    idempotency: Option[String],
    inReplyToId: Option[Id],
    quotedStatusId: Option[Id],
    quoteApprovalPolicy: Option[QuoteApprovalPolicy]
)

/** `ScheduledStatus`: what `POST /api/v1/statuses` returns when `scheduled_at` is supplied, and
  * what `/api/v1/scheduled_statuses` manages.
  *
  * `mediaAttachments` is the documented `media_attachments` array; a payload without it normalises
  * to `Nil`, and the default keeps positional construction source-compatible.
  */
final case class ScheduledStatus(
    id: Id,
    scheduledAt: Instant,
    params: ScheduledStatusParams,
    mediaAttachments: List[MediaAttachment] = Nil
)
