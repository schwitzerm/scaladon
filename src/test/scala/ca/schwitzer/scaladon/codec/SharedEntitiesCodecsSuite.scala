package ca.schwitzer.scaladon.codec

import io.circe.parser.decode
import munit.FunSuite

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{MediaAttachment, MediaType, Poll, PollOption, Tag, TagHistory}

class SharedEntitiesCodecsSuite extends FunSuite:

  private val tagJson: String =
    """{
      |  "name": "cats",
      |  "url": "https://code.party/tags/cats",
      |  "history": [
      |    { "day": "1574553600", "uses": "200", "accounts": "100" }
      |  ],
      |  "following": false
      |}""".stripMargin

  private val mediaJson: String =
    """{
      |  "id": "22348641",
      |  "type": "image",
      |  "url": null,
      |  "preview_url": "https://files.code.party/small.jpg",
      |  "remote_url": null,
      |  "text_url": "https://code.party/media/abc",
      |  "description": "a cat",
      |  "blurhash": "UFBWY:8_0Jxv4mx"
      |}""".stripMargin

  private val pollJson: String =
    """{
      |  "id": "34830",
      |  "expires_at": "2026-11-30T12:00:00.000Z",
      |  "expired": false,
      |  "multiple": true,
      |  "votes_count": 10,
      |  "voters_count": 7,
      |  "voted": true,
      |  "own_votes": [1],
      |  "options": [
      |    { "title": "Cats", "votes_count": 6 },
      |    { "title": "Dogs", "votes_count": 4 }
      |  ]
      |}""".stripMargin

  test("a tag decodes its history") {
    val tag = decode[Tag](tagJson).toOption.get
    assertEquals(tag.name, "cats")
    assertEquals(tag.url, "https://code.party/tags/cats")
    assertEquals(tag.history, List(TagHistory("1574553600", 200, 100)))
    assertEquals(tag.following, Some(false))
  }

  test("a tag without history or following still decodes") {
    val tag = decode[Tag]("""{"name":"cats","url":"https://x/tags/cats"}""").toOption.get
    assertEquals(tag.history, Nil)
    assertEquals(tag.following, None)
  }

  test("a media attachment decodes the async upload shape with a null url") {
    val media = decode[MediaAttachment](mediaJson).toOption.get
    assertEquals(media.id.value, "22348641")
    assertEquals(media.kind, MediaType.Image)
    assertEquals(media.url, None)
    assertEquals(media.previewUrl, Some("https://files.code.party/small.jpg"))
    assertEquals(media.textUrl, Some("https://code.party/media/abc"))
    assertEquals(media.description, Some("a cat"))
  }

  test("an unknown media type fails decoding") {
    assert(decode[MediaAttachment](mediaJson.replace("\"image\"", "\"hologram\"")).isLeft)
  }

  test("a poll decodes its options, counts and own votes") {
    val poll = decode[Poll](pollJson).toOption.get
    assertEquals(poll.id.value, "34830")
    assertEquals(poll.expired, false)
    assertEquals(poll.multiple, true)
    assertEquals(poll.votesCount, 10)
    assertEquals(poll.votersCount, Some(7))
    assertEquals(poll.voted, Some(true))
    assertEquals(poll.ownVotes, Some(List(1)))
    assertEquals(poll.options, List(PollOption("Cats", 6), PollOption("Dogs", 4)))
  }

  test("a poll may have no expiry and no viewer state") {
    val poll = decode[Poll](
      """{"id":"1","expires_at":null,"expired":true,"multiple":false,"votes_count":0,
        |"voters_count":null,"voted":null,"own_votes":null,
        |"options":[{"title":"Yes","votes_count":0}]}""".stripMargin
    ).toOption.get
    assertEquals(poll.expiresAt, None)
    assertEquals(poll.votersCount, None)
    assertEquals(poll.voted, None)
    assertEquals(poll.ownVotes, None)
  }
