package ca.schwitzer.scaladon.codec

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.SearchResults

class SearchResultsCodecsSuite extends FunSuite:

  /** Trimmed from the documented `Search` entity payload: string ids, a `Z` timestamp, numeric
    * strings for tag counts, a null `following`, and the 4.6-only `collections` key the decoder
    * does not model.
    */
  private val payload: Json = parse(
    """{
      |  "accounts": [
      |    {
      |      "id": "180744",
      |      "username": "catstar",
      |      "acct": "catstar@catgram.jp",
      |      "display_name": "catstar",
      |      "note": "<p>cats</p>",
      |      "url": "https://catgram.jp/@catstar",
      |      "uri": "https://catgram.jp/users/catstar",
      |      "avatar": "https://catgram.jp/a.png",
      |      "header": "https://catgram.jp/h.png",
      |      "locked": false,
      |      "bot": false,
      |      "discoverable": true,
      |      "created_at": "2017-04-05T12:00:00.000Z",
      |      "statuses_count": 12,
      |      "followers_count": 34,
      |      "following_count": 56,
      |      "fields": []
      |    }
      |  ],
      |  "statuses": [
      |    {
      |      "id": "103085519055545958",
      |      "uri": "https://mspsocial.net/users/alice/statuses/103085519055545958",
      |      "url": "https://mspsocial.net/@alice/103085519055545958",
      |      "account": {
      |        "id": "7",
      |        "username": "alice",
      |        "acct": "alice",
      |        "display_name": "Alice",
      |        "note": "",
      |        "url": "https://mspsocial.net/@alice",
      |        "avatar": "https://mspsocial.net/a.png",
      |        "header": "https://mspsocial.net/h.png",
      |        "locked": false,
      |        "created_at": "2017-04-05T12:00:00.000Z"
      |      },
      |      "content": "<p>cats<br>cats never change</p>",
      |      "created_at": "2019-11-05T13:23:09.000Z",
      |      "visibility": "public",
      |      "in_reply_to_id": null,
      |      "in_reply_to_account_id": null,
      |      "spoiler_text": "",
      |      "sensitive": false,
      |      "reblog": null,
      |      "reblogs_count": 2,
      |      "favourites_count": 3,
      |      "favourited": true,
      |      "reblogged": false,
      |      "language": "en",
      |      "card": null,
      |      "poll": null
      |    }
      |  ],
      |  "hashtags": [
      |    {
      |      "id": "205",
      |      "name": "cats",
      |      "url": "https://mastodon.social/tags/cats",
      |      "history": [
      |        {"day": "1574553600", "uses": "10", "accounts": "9"}
      |      ],
      |      "following": null
      |    },
      |    {
      |      "id": "708",
      |      "name": "catsofmastodon",
      |      "url": "https://mastodon.social/tags/catsofmastodon",
      |      "history": [
      |        {"day": "1574553600", "uses": "6", "accounts": "5"}
      |      ]
      |    }
      |  ],
      |  "collections": [
      |    {"id": "123456789012345678", "name": "Cats", "description": "Posters who are cats"}
      |  ]
      |}""".stripMargin
  ).toOption.get

  test("decodes accounts, statuses and hashtags, ignoring the unmodelled collections key") {
    val results = payload.as[SearchResults].toOption.get
    assertEquals(results.accounts.map(_.id.value), List("180744"))
    assertEquals(results.accounts.head.acct, "catstar@catgram.jp")
    assertEquals(results.accounts.head.discoverable, Some(true))
    assertEquals(results.statuses.map(_.id.value), List("103085519055545958"))
    assertEquals(results.statuses.head.visibility.wire, "public")
    assertEquals(results.statuses.head.language, Some("en"))
    assertEquals(results.hashtags.map(_.name), List("cats", "catsofmastodon"))
    assertEquals(
      results.hashtags.head.history.map(h => (h.day, h.uses, h.accounts)),
      List(("1574553600", 10, 9))
    )
    assertEquals(results.hashtags.head.following, None)
    assertEquals(results.hashtags(1).history.map(_.uses), List(6))
  }

  test("a category that is absent or null decodes to an empty list") {
    val results = parse("""{"accounts":[],"statuses":null}""")
      .toOption
      .get
      .as[SearchResults]
      .toOption
      .get
    assertEquals(results.accounts, Nil)
    assertEquals(results.statuses, Nil)
    assertEquals(results.hashtags, Nil)
  }

  test("a wrong-typed category is a decode failure") {
    val bad = parse("""{"accounts":"nope"}""").toOption.get
    assert(bad.as[SearchResults].isLeft)
  }
