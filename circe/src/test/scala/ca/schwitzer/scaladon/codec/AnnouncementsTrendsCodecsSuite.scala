package ca.schwitzer.scaladon.codec

import io.circe.parser.decode
import munit.FunSuite

import java.time.Instant

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Announcement, PreviewCardType, TrendLink}

class AnnouncementsTrendsCodecsSuite extends FunSuite:

  private val announcementJson: String =
    """{
      |  "id": "8",
      |  "content": "<p>Looks like there was an issue processing audio attachments.</p>",
      |  "starts_at": "2026-07-01T00:00:00.000Z",
      |  "ends_at": "2026-07-08T00:00:00.000Z",
      |  "all_day": true,
      |  "published_at": "2026-07-03T01:27:38.726Z",
      |  "updated_at": "2026-07-03T01:27:38.752Z",
      |  "read": true,
      |  "mentions": [
      |    {
      |      "id": "1",
      |      "username": "alice",
      |      "url": "https://code.party/@alice",
      |      "acct": "alice@code.party"
      |    }
      |  ],
      |  "statuses": [
      |    { "id": "42", "url": "https://code.party/@alice/42" }
      |  ],
      |  "tags": [
      |    { "name": "server", "url": "https://code.party/tags/server" }
      |  ],
      |  "emojis": [
      |    {
      |      "shortcode": "blobaww",
      |      "url": "https://files.code.party/blobaww.png",
      |      "static_url": "https://files.code.party/blobaww_static.png",
      |      "visible_in_picker": true,
      |      "category": null
      |    }
      |  ],
      |  "reactions": [
      |    {
      |      "name": "bongoCat",
      |      "count": 9,
      |      "me": false,
      |      "url": "https://files.code.party/bongo.png",
      |      "static_url": "https://files.code.party/bongo_static.png"
      |    },
      |    { "name": "🤔", "count": 1, "me": true }
      |  ],
      |  "reactions_allowed": true
      |}""".stripMargin

  private val trendLinkJson: String =
    """{
      |  "url": "https://www.nbcnews.com/specials/plan-your-vote-2022-elections/index.html",
      |  "title": "Plan Your Vote: 2022 Elections",
      |  "description": "Everything you need to know about the voting rules where you live.",
      |  "type": "link",
      |  "author_name": "NBC News",
      |  "author_url": "",
      |  "provider_name": "NBC News",
      |  "provider_url": "",
      |  "html": "",
      |  "width": 400,
      |  "height": 225,
      |  "image": "https://files.code.party/card.jpeg",
      |  "embed_url": "",
      |  "blurhash": "UcQmF#ay~qofj[WBj[j[~qof9Fayofofayay",
      |  "history": [
      |    { "day": "1661817600", "accounts": "7", "uses": "7" },
      |    { "day": "1661731200", "accounts": "23", "uses": "23" }
      |  ]
      |}""".stripMargin

  test("an announcement decodes its schedule, nested entities and reactions") {
    val announcement = decode[Announcement](announcementJson).toOption.get
    assertEquals(announcement.id.value, "8")
    assertEquals(announcement.allDay, true)
    assertEquals(announcement.startsAt, Some(Instant.parse("2026-07-01T00:00:00Z")))
    assertEquals(announcement.endsAt, Some(Instant.parse("2026-07-08T00:00:00Z")))
    assertEquals(announcement.publishedAt, Instant.parse("2026-07-03T01:27:38.726Z"))
    assertEquals(announcement.read, Some(true))
    assertEquals(announcement.reactionsAllowed, true)
    assertEquals(announcement.mentions.map(_.acct), List("alice@code.party"))
    assertEquals(announcement.statuses.map(_.id.value), List("42"))
    assertEquals(announcement.tags.map(_.url), List("https://code.party/tags/server"))
    assertEquals(announcement.emojis.map(_.category), List(None))
    assertEquals(announcement.reactions.map(_.count), List(9, 1))
    assertEquals(announcement.reactions(1).me, true)
    assertEquals(announcement.reactions(1).url, None)
  }

  test("an announcement without read or reactions_allowed decodes conservatively") {
    val json =
      """{"id":"9","content":"<p>hi</p>","starts_at":null,"ends_at":null,"all_day":false,
        |"published_at":"2026-07-03T01:27:38.726Z","updated_at":"2026-07-03T01:27:38.752Z"}""".stripMargin
    val announcement = decode[Announcement](json).toOption.get
    assertEquals(announcement.read, None)
    assertEquals(announcement.reactionsAllowed, false)
    assertEquals(announcement.startsAt, None)
    assertEquals(announcement.endsAt, None)
    assertEquals(announcement.mentions, Nil)
    assertEquals(announcement.reactions, Nil)
    assertEquals(announcement.emojis, Nil)
  }

  test("a trending link decodes its type and string-valued history") {
    val link = decode[TrendLink](trendLinkJson).toOption.get
    assertEquals(link.cardType, PreviewCardType.Link)
    assertEquals(link.image, Some("https://files.code.party/card.jpeg"))
    assertEquals(link.history.map(_.uses), List(7, 23))
    assertEquals(link.width, 400)
  }

  test("a trending link without history or image decodes with defaults") {
    val json =
      """{"url":"https://example.com","title":"Example","type":"rich",
        |"description":"","author_name":"","author_url":"","provider_name":"","provider_url":"",
        |"html":"","width":0,"height":0,"image":null,"embed_url":"","blurhash":null}""".stripMargin
    val link = decode[TrendLink](json).toOption.get
    assertEquals(link.cardType, PreviewCardType.Rich)
    assertEquals(link.history, Nil)
    assertEquals(link.image, None)
    assertEquals(link.blurhash, None)
  }

  test("an unknown trending link type fails decoding instead of leaking a string") {
    assert(decode[TrendLink](trendLinkJson.replace("\"type\": \"link\"", "\"type\": \"hologram\"")).isLeft)
  }
