package ca.schwitzer.scaladon.http

/** A Mastodon instance address, e.g. `Host("code.party")`.
  *
  * The legacy client took a bare host and assumed HTTPS in one place and built an absolute URL in
  * another. Here the scheme is decided once.
  */
final case class Host(value: String):
  def baseUrl: String =
    val trimmed = value.stripSuffix("/")
    if trimmed.startsWith("http://") || trimmed.startsWith("https://") then trimmed
    else s"https://$trimmed"
