package ca.schwitzer.scaladon.codec

import io.circe.parser.decode
import munit.FunSuite

import java.time.Instant

import ca.schwitzer.scaladon.domain.{
  Account,
  AccountState,
  AccountStateError,
  CredentialAccount,
  Profile,
  Relationship,
  Visibility
}
import ca.schwitzer.scaladon.codec.Codecs.given

class AccountsCodecsSuite extends FunSuite:

  private val movedJson: String =
    """{
      |  "id": "999",
      |  "username": "old",
      |  "acct": "old",
      |  "display_name": "Old",
      |  "note": "",
      |  "avatar": "https://code.party/old.png",
      |  "header": "https://code.party/old-header.png",
      |  "created_at": "2016-01-01T00:00:00.000Z"
      |}""".stripMargin

  private val accountJson: String =
    s"""{
       |  "id": "23634",
       |  "username": "noiob",
       |  "acct": "noiob@awoo.space",
       |  "display_name": "shark",
       |  "note": "<p>hi</p>",
       |  "url": "https://awoo.space/@noiob",
       |  "uri": "https://awoo.space/users/noiob",
       |  "avatar": "https://code.party/a.png",
       |  "avatar_static": "https://code.party/a.png",
       |  "avatar_description": "a shark",
       |  "header": "https://code.party/h.png",
       |  "header_static": "https://code.party/h.png",
       |  "header_description": "sharks",
       |  "locked": false,
       |  "bot": true,
       |  "group": false,
       |  "discoverable": true,
       |  "indexable": true,
       |  "created_at": "2017-02-08T02:00:53.274Z",
       |  "last_status_at": "2019-11-17",
       |  "statuses_count": 28468,
       |  "followers_count": 547,
       |  "following_count": 404,
       |  "hide_collections": false,
       |  "show_media": true,
       |  "show_media_replies": true,
       |  "show_featured": true,
       |  "fields": [
       |    { "name": "Website", "value": "https://noiob.example", "verified_at": "2019-11-10T10:31:10.744+00:00" }
       |  ],
       |  "moved": $movedJson
       |}""".stripMargin

  private val credentialJson: String =
    accountJson.stripSuffix("}") +
      """,
        |  "source": {
        |    "privacy": "unlisted",
        |    "sensitive": true,
        |    "spoiler_field": true,
        |    "language": "en",
        |    "note": "raw bio",
        |    "fields": [
        |      { "name": "Website", "value": "https://noiob.example", "verified_at": null }
        |    ],
        |    "follow_requests_count": 3,
        |    "attribution_domains": ["example.com"]
        |  },
        |  "state": {
        |    "functional": true,
        |    "confirmed": true,
        |    "approved": true
        |  }
        |}""".stripMargin

  test("account decodes the current fields, a date last_status_at and a recursive moved account") {
    val account = decode[Account](accountJson).toOption.get
    assertEquals(account.bot, true)
    assertEquals(account.avatarDescription, Some("a shark"))
    assertEquals(account.discoverable, Some(true))
    assertEquals(account.statusesCount, 28468)
    assertEquals(account.lastStatusAt, Some(Instant.parse("2019-11-17T00:00:00Z")))
    assertEquals(account.fields.head.verifiedAt, Some(Instant.parse("2019-11-10T10:31:10.744Z")))
    assertEquals(account.moved.map(_.username), Some("old"))
  }

  test("account defaults the not-yet-sent fields of an older payload") {
    val minimal =
      """{
        |  "id": "1",
        |  "username": "alice",
        |  "acct": "alice",
        |  "display_name": "Alice",
        |  "note": "",
        |  "avatar": "https://code.party/a.png",
        |  "header": "https://code.party/h.png",
        |  "created_at": "2017-04-05T12:00:00.000Z"
        |}""".stripMargin
    val account = decode[Account](minimal).toOption.get
    assertEquals(account.url, None)
    assertEquals(account.bot, false)
    assertEquals(account.indexable, false)
    assertEquals(account.discoverable, None)
    assertEquals(account.lastStatusAt, None)
    assertEquals(account.statusesCount, 0)
    assertEquals(account.fields, Nil)
    assertEquals(account.moved, None)
  }

  test("credential account decodes source and state, with no role") {
    val credential = decode[CredentialAccount](credentialJson).toOption.get
    assertEquals(credential.source.note, "raw bio")
    assertEquals(credential.source.privacy, Visibility.Unlisted)
    assertEquals(credential.source.sensitive, true)
    assertEquals(credential.source.spoilerField, true)
    assertEquals(credential.account.id.value, "23634")
    assertEquals(credential.state.map(_.functional), Some(true))
    assertEquals(credential.state.flatMap(_.errorCode), None)
    assertEquals(credential.role, None)
  }

  test("an unknown account state error code fails decoding") {
    val broken =
      """{"functional": false, "confirmed": false, "approved": false, "error_code": "new_reason"}"""
    val result = decode[AccountState](broken)
    assert(result.isLeft, s"expected a decode failure, got $result")
  }

  test("relationship keeps languages null as None and parses a datetime mute expiry") {
    val json =
      """{
        |  "id": "3",
        |  "following": false,
        |  "showing_reblogs": false,
        |  "notifying": false,
        |  "languages": null,
        |  "followed_by": true,
        |  "blocking": false,
        |  "blocked_by": false,
        |  "muting": true,
        |  "muting_notifications": true,
        |  "muting_expires_at": "2026-11-27T10:00:00.000Z",
        |  "requested": false,
        |  "requested_by": false,
        |  "domain_blocking": false,
        |  "endorsed": false
        |}""".stripMargin
    val relationship = decode[Relationship](json).toOption.get
    assertEquals(relationship.languages, None)
    assertEquals(relationship.mutingExpiresAt, Some(Instant.parse("2026-11-27T10:00:00Z")))
    assertEquals(relationship.note, "")
  }

  test("profile keeps raw values alongside formatted ones and nullable images") {
    val json =
      """{
        |  "id": "116222600881276277",
        |  "display_name": "Docs",
        |  "note": "raw bio",
        |  "fields": [{ "name": "pronouns", "value": "it/its", "verified_at": null }],
        |  "avatar": null,
        |  "avatar_static": null,
        |  "avatar_description": "",
        |  "header": null,
        |  "header_static": null,
        |  "header_description": "",
        |  "locked": false,
        |  "bot": false,
        |  "hide_collections": null,
        |  "discoverable": true,
        |  "indexable": true,
        |  "show_media": true,
        |  "show_media_replies": false,
        |  "show_featured": true,
        |  "attribution_domains": ["example.com"],
        |  "formatted_note": "<p>raw bio</p>",
        |  "formatted_fields": [{ "name": "pronouns", "value": "<a>it/its</a>", "verified_at": null }]
        |}""".stripMargin
    val profile = decode[Profile](json).toOption.get
    assertEquals(profile.note, "raw bio")
    assertEquals(profile.formattedNote, Some("<p>raw bio</p>"))
    assertEquals(profile.fields.map(_.value), List("it/its"))
    assertEquals(profile.formattedFields.map(_.value), List("<a>it/its</a>"))
    assertEquals(profile.avatar, None)
    assertEquals(profile.showMediaReplies, false)
    assertEquals(AccountStateError.fromWire("account_deleted"), Some(AccountStateError.AccountDeleted))
  }
