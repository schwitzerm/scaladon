package ca.schwitzer.scaladon.codec

import io.circe.parser.decode
import munit.FunSuite

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Id, MastodonList, RepliesPolicy, Report, ReportCategory}

class ReportsListsCodecsSuite extends FunSuite:

  private val targetAccountJson: String =
    """{
      |  "id": "108366849347798387",
      |  "username": "Baluke",
      |  "acct": "Baluke",
      |  "display_name": "Baluke Dental Studios",
      |  "note": "<p>A lab.</p>",
      |  "url": "https://mastodon.social/@Baluke",
      |  "avatar": "https://files.mastodon.social/a.png",
      |  "header": "https://files.mastodon.social/h.png",
      |  "locked": false,
      |  "created_at": "2022-05-26T00:00:00.000Z"
      |}""".stripMargin

  private val reportJson: String =
    s"""{
       |  "id": "48914",
       |  "action_taken": false,
       |  "action_taken_at": null,
       |  "category": "spam",
       |  "comment": "Spam account",
       |  "forwarded": false,
       |  "created_at": "2022-08-25T09:56:16.763Z",
       |  "status_ids": ["108882889550545820"],
       |  "collection_ids": [],
       |  "rule_ids": null,
       |  "target_account": $targetAccountJson
       |}""".stripMargin

  test("a report decodes its category, comment, id arrays and target account") {
    val report = decode[Report](reportJson).toOption.get
    assertEquals(report.id, Id("48914"))
    assertEquals(report.category, ReportCategory.Spam)
    assertEquals(report.comment, Some("Spam account"))
    assertEquals(report.actionTaken, false)
    assertEquals(report.actionTakenAt, None)
    assertEquals(report.forwarded, false)
    assertEquals(report.statusIds, List(Id("108882889550545820")))
    assertEquals(report.collectionIds, Nil)
    assertEquals(report.ruleIds, Nil)
    assertEquals(report.targetAccount.username, "Baluke")
  }

  test("a report normalises a null comment to None and keeps the rule ids") {
    val json = reportJson
      .replace("\"category\": \"spam\"", "\"category\": \"legal\"")
      .replace("\"comment\": \"Spam account\"", "\"comment\": \"\"")
      .replace("\"rule_ids\": null", "\"rule_ids\": [\"3\", \"7\"]")
      .replace("\"action_taken\": false", "\"action_taken\": true")
      .replace("\"action_taken_at\": null", "\"action_taken_at\": \"2022-08-26T00:00:00.000Z\"")
    val report = decode[Report](json).toOption.get
    assertEquals(report.category, ReportCategory.Legal)
    assertEquals(report.comment, None)
    assertEquals(report.actionTaken, true)
    assertEquals(report.actionTakenAt.map(_.toString), Some("2022-08-26T00:00:00Z"))
    assertEquals(report.ruleIds, List(Id("3"), Id("7")))
  }

  test("an unknown report category fails decoding instead of leaking a string") {
    assert(decode[Report](reportJson.replace("\"spam\"", "\"harassment\"")).isLeft)
  }

  test("a list decodes its title, replies policy and exclusivity") {
    val list = decode[MastodonList](
      """{"id":"12249","title":"Friends","replies_policy":"none","exclusive":true}"""
    ).toOption.get
    assertEquals(list.id, Id("12249"))
    assertEquals(list.title, "Friends")
    assertEquals(list.repliesPolicy, RepliesPolicy.NoReplies)
    assertEquals(list.exclusive, true)
  }

  test("a list without exclusive defaults it to false") {
    val list = decode[MastodonList](
      """{"id":"12249","title":"Friends","replies_policy":"followed"}"""
    ).toOption.get
    assertEquals(list.exclusive, false)
  }

  test("an unknown replies policy fails decoding") {
    val json = """{"id":"12249","title":"Friends","replies_policy":"some","exclusive":false}"""
    assert(decode[MastodonList](json).isLeft)
  }
