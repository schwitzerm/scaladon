package ca.schwitzer.scaladon.codec

import io.circe.parser.decode
import munit.FunSuite

import java.time.Instant

import ca.schwitzer.scaladon.domain.{Account, Status, Visibility}
import ca.schwitzer.scaladon.codec.Codecs.given

class CodecsSuite extends FunSuite:

  private val accountJson: String =
    """{
      |  "id": "9223372036854775807",
      |  "username": "alice",
      |  "acct": "alice@code.party",
      |  "display_name": "Alice",
      |  "note": "<p>hi</p>",
      |  "url": "https://code.party/@alice",
      |  "avatar": "https://code.party/avatar.png",
      |  "header": "https://code.party/header.png",
      |  "locked": false,
      |  "created_at": "2017-04-05T12:00:00.000Z"
      |}""".stripMargin

  private val statusJson: String =
    s"""{
       |  "id": "42",
       |  "uri": "tag:code.party,2017-04-05:objectId=1",
       |  "url": "https://code.party/@alice/42",
       |  "account": $accountJson,
       |  "content": "<p>Hello</p>",
       |  "created_at": "2017-04-05T12:00:00.000Z",
       |  "visibility": "public",
       |  "in_reply_to_id": null,
       |  "in_reply_to_account_id": null,
       |  "spoiler_text": "",
       |  "sensitive": false,
       |  "reblog": null,
       |  "reblogs_count": 3,
       |  "favourites_count": 4,
       |  "favourited": false,
       |  "reblogged": false
       |}""".stripMargin

  test("ids decode as strings beyond Int.MaxValue") {
    val account = decode[Account](accountJson).toOption.get
    assertEquals(account.id.value, "9223372036854775807")
    assertEquals(account.displayName, "Alice")
  }

  test("created_at decodes RFC 3339 with a literal Z") {
    val account = decode[Account](accountJson).toOption.get
    assertEquals(account.createdAt, Instant.parse("2017-04-05T12:00:00.000Z"))
  }

  test("status decodes an empty spoiler_text as None and keeps counts") {
    val status = decode[Status](statusJson).toOption.get
    assertEquals(status.id.value, "42")
    assertEquals(status.visibility, Visibility.Public)
    assertEquals(status.spoilerText, None)
    assertEquals(status.inReplyToId, None)
    assertEquals(status.reblogsCount, 3)
    assertEquals(status.favouritesCount, 4)
    assertEquals(status.url, Some("https://code.party/@alice/42"))
  }

  test("an unknown visibility fails decoding with a typed error") {
    val broken = statusJson.replace("\"public\"", "\"friends-only\"")
    val result = decode[Status](broken)
    assert(result.isLeft, s"expected a decode failure, got $result")
  }

  test("a status without optional fields still decodes") {
    val minimal =
      s"""{
         |  "id": "42",
         |  "uri": "tag:code.party,2017-04-05:objectId=1",
         |  "account": $accountJson,
         |  "content": "<p>Hello</p>",
         |  "created_at": "2017-04-05T12:00:00.000Z",
         |  "visibility": "unlisted",
         |  "spoiler_text": null,
         |  "reblogs_count": 0,
         |  "favourites_count": 0
         |}""".stripMargin

    val status = decode[Status](minimal).toOption.get
    assertEquals(status.sensitive, false)
    assertEquals(status.favourited, false)
    assertEquals(status.reblogged, false)
    assertEquals(status.url, None)
    assertEquals(status.spoilerText, None)
    assertEquals(status.reblog, None)
    assertEquals(status.visibility, Visibility.Unlisted)
  }
