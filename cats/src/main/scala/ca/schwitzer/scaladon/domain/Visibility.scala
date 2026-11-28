package ca.schwitzer.scaladon.domain

import cats.Show

/** Post visibility, with the exact strings the Mastodon API accepts and returns. */
enum Visibility(val wire: String):
  case Public   extends Visibility("public")
  case Unlisted extends Visibility("unlisted")
  case Private  extends Visibility("private")
  case Direct   extends Visibility("direct")
  case Default  extends Visibility("")

object Visibility:
  def all: List[Visibility] = values.toList

  /** Total inverse of [[Visibility.wire]]; `None` for values this version does not know. */
  def fromWire(value: String): Option[Visibility] = all.find(_.wire == value)

  given Show[Visibility] = Show.show(_.wire)
