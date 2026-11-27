package ca.schwitzer.scaladon.domain

/** What `POST /api/v1/statuses` returns: a published status, or a scheduled one.
  *
  * The API signals the difference only by `scheduled_at` being present in the JSON, so the result
  * is a sum type rather than an optional pairing; callers cannot forget to check.
  */
enum StatusPostResult:
  case Posted(status: Status)
  case Scheduled(value: ScheduledStatus)
