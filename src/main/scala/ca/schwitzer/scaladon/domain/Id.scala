package ca.schwitzer.scaladon.domain

import cats.{Eq, Order, Show}

/** A Mastodon object id.
  *
  * The wire format is a decimal string and the value routinely exceeds `Int.MaxValue`. The legacy
  * client typed every id as `Int` and could not decode real payloads (see `legacy/README.md`, #4);
  * this is a zero-cost newtype over `String`, so nothing can silently coerce an id to a number.
  */
opaque type Id = String

object Id:
  def apply(value: String): Id = value

  extension (id: Id) def value: String = id

  given Eq[Id] = Eq.fromUniversalEquals
  given Order[Id] = Order.fromOrdering(using Ordering.String)
  given Show[Id] = Show.show(_.value)
