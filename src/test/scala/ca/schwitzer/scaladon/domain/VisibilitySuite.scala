package ca.schwitzer.scaladon.domain

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

class VisibilitySuite extends ScalaCheckSuite:

  test("wire values match the Mastodon API, including the default") {
    assertEquals(Visibility.Public.wire, "public")
    assertEquals(Visibility.Unlisted.wire, "unlisted")
    assertEquals(Visibility.Private.wire, "private")
    assertEquals(Visibility.Direct.wire, "direct")
    assertEquals(Visibility.Default.wire, "")
  }

  test("unknown wire values are rejected rather than defaulted") {
    assertEquals(Visibility.fromWire("friends-only"), None)
  }

  property("fromWire inverts wire for every known value") {
    val values = Gen.oneOf(
      Visibility.Public,
      Visibility.Unlisted,
      Visibility.Private,
      Visibility.Direct,
      Visibility.Default
    )
    Prop.forAll(values) { visibility =>
      Prop(Visibility.fromWire(visibility.wire) == Some(visibility))
    }
  }
