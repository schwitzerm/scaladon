package ca.schwitzer.scaladon.codec

import io.circe.parser.decode
import munit.FunSuite

import java.time.Instant

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{
  Filter,
  FilterAction,
  FilterContext,
  FilterKeyword,
  FilterStatus,
  Id,
  Marker,
  MarkerTimeline
}

class FiltersMarkersCodecsSuite extends FunSuite:

  private val filterJson: String =
    """{
      |  "id": "19972",
      |  "title": "Test filter",
      |  "context": ["home", "notifications"],
      |  "expires_at": "2022-09-20T17:27:39.296Z",
      |  "filter_action": "warn",
      |  "keywords": [
      |    { "id": "1197", "keyword": "bad word", "whole_word": false }
      |  ],
      |  "statuses": [
      |    { "id": "1", "status_id": "109031743575371913" }
      |  ]
      |}""".stripMargin

  private val markersJson: String =
    """{
      |  "notifications": {
      |    "last_read_id": "35098814",
      |    "version": 361,
      |    "updated_at": "2019-11-26T22:37:25.239Z"
      |  },
      |  "home": {
      |    "last_read_id": "103206604258487607",
      |    "version": 468,
      |    "updated_at": "2019-11-26T22:37:25.235Z"
      |  }
      |}""".stripMargin

  test("a filter decodes its context, action, keywords and statuses") {
    val filter = decode[Filter](filterJson).toOption.get
    assertEquals(filter.id, Id("19972"))
    assertEquals(filter.title, "Test filter")
    assertEquals(filter.context, List(FilterContext.Home, FilterContext.Notifications))
    assertEquals(filter.expiresAt, Some(Instant.parse("2022-09-20T17:27:39.296Z")))
    assertEquals(filter.filterAction, FilterAction.Warn)
    assertEquals(filter.keywords, List(FilterKeyword(Id("1197"), "bad word", false)))
    assertEquals(filter.statuses, List(FilterStatus(Id("1"), Id("109031743575371913"))))
  }

  test("a filter without keywords, statuses or expiry decodes to empty lists and no expiry") {
    val minimal =
      """{
        |  "id": "25933",
        |  "title": "test",
        |  "context": ["public"],
        |  "expires_at": null,
        |  "filter_action": "blur"
        |}""".stripMargin
    val filter = decode[Filter](minimal).toOption.get
    assertEquals(filter.expiresAt, None)
    assertEquals(filter.filterAction, FilterAction.Blur)
    assertEquals(filter.keywords, Nil)
    assertEquals(filter.statuses, Nil)
  }

  test("an unknown filter context fails decoding instead of leaking a string") {
    val payload = filterJson.replace("\"home\", ", "\"spaces\", ")
    assert(decode[Filter](payload).isLeft)
  }

  test("an unknown filter action fails decoding instead of defaulting to warn") {
    val payload = filterJson.replace("\"warn\"", "\"mute\"")
    assert(decode[Filter](payload).isLeft)
  }

  test("the markers hash decodes to a typed map keyed by timeline") {
    val markers = decode[Map[MarkerTimeline, Marker]](markersJson).toOption.get
    assertEquals(markers.keySet, Set(MarkerTimeline.Home, MarkerTimeline.Notifications))
    assertEquals(
      markers(MarkerTimeline.Home),
      Marker(Id("103206604258487607"), 468, Instant.parse("2019-11-26T22:37:25.235Z"))
    )
    assertEquals(markers(MarkerTimeline.Notifications).version, 361)
  }

  test("an unknown marker timeline key fails decoding rather than being dropped") {
    val payload = markersJson.replace("\"home\"", "\"direct\"")
    assert(decode[Map[MarkerTimeline, Marker]](payload).isLeft)
  }
