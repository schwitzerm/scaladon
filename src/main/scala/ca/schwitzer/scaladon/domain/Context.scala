package ca.schwitzer.scaladon.domain

/** The conversation tree around a status: parents and children, as returned by
  * `GET /api/v1/statuses/:id/context`.
  */
final case class Context(ancestors: List[Status], descendants: List[Status])

/** The decoded `Mastodon-Async-Refresh` response header.
  *
  * Format: `id="<string>", retry=<int>, result_count=<int>`, where `result_count` is optional.
  * `retry` is the minimum number of seconds to wait before polling. The header is experimental
  * (Mastodon 4.5+), so it is returned as an optional value alongside the response body.
  */
final case class AsyncRefresh(id: String, retry: Int, resultCount: Option[Int])
