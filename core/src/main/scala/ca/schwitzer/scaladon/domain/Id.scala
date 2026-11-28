package ca.schwitzer.scaladon.domain

/** A Mastodon object id.
  *
  * The wire format is a decimal string and the value routinely exceeds `Int.MaxValue`; real
  * payloads cannot be decoded into an `Int`. This is a zero-cost newtype over `String`, so nothing
  * can silently coerce an id to a number.
  */
opaque type Id = String

object Id:
  def apply(value: String): Id = value

  extension (id: Id) def value: String = id

