package ca.schwitzer.scaladon.codec

import io.circe.parser.decode
import munit.FunSuite

import java.time.Instant

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{
  Context,
  PreviewCard,
  PreviewCardType,
  Quote,
  QuoteApprovalPolicy,
  QuoteState,
  ScheduledStatus,
  ShallowQuote,
  Status,
  StatusPostResult
}

class StatusesCodecsSuite extends FunSuite:

  private val accountJson: String =
    """{
      |  "id": "7",
      |  "username": "alice",
      |  "acct": "alice",
      |  "display_name": "Alice",
      |  "note": "",
      |  "url": "https://code.party/@alice",
      |  "avatar": "https://code.party/a.png",
      |  "header": "https://code.party/h.png",
      |  "locked": false,
      |  "created_at": "2017-04-05T12:00:00.000Z"
      |}""".stripMargin

  private val cardJson: String =
    """{
      |  "url": "https://example.com/a",
      |  "title": "An article",
      |  "description": "About things",
      |  "type": "link",
      |  "author_name": "",
      |  "author_url": "",
      |  "provider_name": "Example",
      |  "provider_url": "https://example.com",
      |  "html": "",
      |  "width": 400,
      |  "height": 200,
      |  "image": null,
      |  "embed_url": "",
      |  "blurhash": null,
      |  "missing_attribution": false
      |}""".stripMargin

  private val quotedStatusJson: String =
    s"""{
       |  "id": "99",
       |  "uri": "https://code.party/users/bob/statuses/99",
       |  "url": "https://code.party/@bob/99",
       |  "account": $accountJson,
       |  "content": "<p>quoted</p>",
       |  "created_at": "2025-08-27T10:00:00.000Z",
       |  "visibility": "public",
       |  "in_reply_to_id": null,
       |  "in_reply_to_account_id": null,
       |  "spoiler_text": "",
       |  "sensitive": false,
       |  "reblog": null,
       |  "reblogs_count": 0,
       |  "favourites_count": 0
       |}""".stripMargin

  private val statusJson: String =
    s"""{
       |  "id": "115107232286434584",
       |  "uri": "https://code.party/users/alice/statuses/115",
       |  "url": "https://code.party/@alice/115",
       |  "account": $accountJson,
       |  "content": "<p>look at this</p>",
       |  "created_at": "2025-08-28T16:02:57.029Z",
       |  "visibility": "public",
       |  "in_reply_to_id": null,
       |  "in_reply_to_account_id": null,
       |  "spoiler_text": "",
       |  "sensitive": false,
       |  "reblog": null,
       |  "replies_count": 3,
       |  "reblogs_count": 1,
       |  "favourites_count": 6,
       |  "quotes_count": 2,
       |  "favourited": false,
       |  "reblogged": false,
       |  "muted": true,
       |  "bookmarked": true,
       |  "pinned": true,
       |  "edited_at": "2025-09-05T00:33:20.309Z",
       |  "language": "en",
       |  "text": "look at this",
       |  "card": $cardJson,
       |  "quote": {
       |    "state": "accepted",
       |    "quoted_status": $quotedStatusJson,
       |    "quoted_status_id": "99"
       |  }
       |}""".stripMargin

  private val scheduledJson: String =
    """{
      |  "id": "3221",
      |  "scheduled_at": "2022-09-29T00:00:00.000Z",
      |  "params": {
      |    "text": "test post",
      |    "media_ids": ["1", "2"],
      |    "sensitive": true,
      |    "spoiler_text": "",
      |    "visibility": "public",
      |    "language": "en",
      |    "scheduled_at": null,
      |    "poll": {
      |      "options": ["a", "b"],
      |      "expires_in": 300,
      |      "multiple": true,
      |      "hide_totals": false
      |    },
      |    "idempotency": null,
      |    "in_reply_to_id": "5",
      |    "application_id": 596551,
      |    "quoted_status_id": "99",
      |    "quote_approval_policy": "followers"
      |  },
      |  "media_attachments": []
      |}""".stripMargin

  test("status decodes the 4.4-5.0 fields, counts and a full quote") {
    val status = decode[Status](statusJson).toOption.get
    assertEquals(status.editedAt, Some(Instant.parse("2025-09-05T00:33:20.309Z")))
    assertEquals(status.bookmarked, true)
    assertEquals(status.muted, true)
    assertEquals(status.pinned, true)
    assertEquals(status.repliesCount, 3)
    assertEquals(status.quotesCount, 2)
    assertEquals(status.language, Some("en"))
    assertEquals(status.text, Some("look at this"))
    assertEquals(status.card.map(_.cardType), Some(PreviewCardType.Link))
    status.quote match
      case Some(Quote(state, quoted, quotedId)) =>
        assertEquals(state, QuoteState.Accepted)
        assertEquals(quoted.map(_.id.value), Some("99"))
        assertEquals(quotedId, Some(ca.schwitzer.scaladon.domain.Id("99")))
      case other => fail(s"expected a quote, got $other")
  }

  test("a status without the optional 4.4-5.0 fields defaults the booleans to false") {
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
    assertEquals(status.bookmarked, false)
    assertEquals(status.muted, false)
    assertEquals(status.pinned, false)
    assertEquals(status.editedAt, None)
    assertEquals(status.quote, None)
    assertEquals(status.card, None)
    assertEquals(status.repliesCount, 0)
    assertEquals(status.quotesCount, 0)
  }

  test("every documented quote state decodes, including the 4.5 additions") {
    QuoteState.all.foreach { state =>
      val json = s"""{"state":"${state.wire}","quoted_status_id":null}"""
      assertEquals(decode[ShallowQuote](json).map(_.state), Right(state), state.wire)
    }
  }

  test("a shallow quote carries the quoted status id") {
    val quote = decode[ShallowQuote](
      """{"state":"blocked_domain","quoted_status_id":"99"}"""
    ).toOption.get
    assertEquals(quote.state, QuoteState.BlockedDomain)
    assertEquals(quote.quotedStatusId.map(_.value), Some("99"))
  }

  test("an unknown quote state fails decoding instead of defaulting") {
    val result = decode[ShallowQuote]("""{"state":"mystery","quoted_status_id":null}""")
    assert(result.isLeft, s"expected a decode failure, got $result")
  }

  test("context decodes ancestors and descendants") {
    val json = s"""{"ancestors":[$quotedStatusJson],"descendants":[$quotedStatusJson]}"""
    val context = decode[Context](json).toOption.get
    assertEquals(context.ancestors.map(_.id.value), List("99"))
    assertEquals(context.descendants.map(_.id.value), List("99"))
  }

  test("a preview card decodes its type and nullable media") {
    val card = decode[PreviewCard](cardJson).toOption.get
    assertEquals(card.cardType, PreviewCardType.Link)
    assertEquals(card.title, "An article")
    assertEquals(card.image, None)
    assertEquals(card.blurhash, None)
    assertEquals(card.missingAttribution, false)
  }

  test("a scheduled status decodes its params, poll and quote policy") {
    val scheduled = decode[ScheduledStatus](scheduledJson).toOption.get
    assertEquals(scheduled.id.value, "3221")
    assertEquals(scheduled.scheduledAt, Instant.parse("2022-09-29T00:00:00.000Z"))
    assertEquals(scheduled.params.mediaIds.map(_.map(_.value)), Some(List("1", "2")))
    assertEquals(scheduled.params.sensitive, Some(true))
    assertEquals(scheduled.params.inReplyToId.map(_.value), Some("5"))
    assertEquals(scheduled.params.poll.map(_.options), Some(List("a", "b")))
    assertEquals(scheduled.params.poll.map(_.multiple), Some(true))
    assertEquals(scheduled.params.quotedStatusId.map(_.value), Some("99"))
    assertEquals(
      scheduled.params.quoteApprovalPolicy,
      Some(QuoteApprovalPolicy.Followers)
    )
  }

  test("post result picks Scheduled when scheduled_at is present") {
    decode[StatusPostResult](scheduledJson) match
      case Right(StatusPostResult.Scheduled(value)) => assertEquals(value.id.value, "3221")
      case other                                    => fail(s"expected Scheduled, got $other")
  }

  test("post result picks Posted when scheduled_at is absent") {
    decode[StatusPostResult](statusJson) match
      case Right(StatusPostResult.Posted(status)) => assertEquals(status.id.value, "115107232286434584")
      case other                                  => fail(s"expected Posted, got $other")
  }
