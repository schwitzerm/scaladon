package ca.schwitzer.scaladon.codec

import io.circe.parser.decode
import io.circe.syntax.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Instant

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, Visibility}

class PrimitiveCodecsSuite extends ScalaCheckSuite:

  property("Id round-trips through JSON as a string") {
    forAll { (value: String) =>
      decode[Id](Id(value).asJson.noSpaces) == Right(Id(value))
    }
  }

  property("Visibility round-trips through every wire value") {
    forAll(Gen.oneOf(Visibility.values.toSeq)) { visibility =>
      decode[Visibility](visibility.asJson.noSpaces) == Right(visibility)
    }
  }

  property("Instant round-trips at second precision") {
    val instants = Gen.choose(-2208988800L, 4102444800L).map(Instant.ofEpochSecond)
    forAll(instants) { instant =>
      decode[Instant](instant.asJson.noSpaces) == Right(instant)
    }
  }

  test("a numeric id is not accepted as an Id") {
    assert(decode[Id]("42").isLeft)
  }

  test("an invalid timestamp is a decode failure, not an exception") {
    assert(decode[Instant]("\"not-a-date\"").isLeft)
  }

  test("an unknown visibility is a decode failure") {
    assert(decode[Visibility]("\"secret\"").isLeft)
  }
