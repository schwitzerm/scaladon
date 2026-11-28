package ca.schwitzer.scaladon.http

/** A Mastodon instance address, e.g. `Host("code.party")`.
  *
  * Callers supply a bare host; the scheme is decided once here.
  */
final case class Host(value: String):
  def baseUrl: String =
    val trimmed = value.stripSuffix("/")
    if trimmed.startsWith("http://") || trimmed.startsWith("https://") then trimmed
    else s"https://$trimmed"
