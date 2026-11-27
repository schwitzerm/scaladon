package ca.schwitzer.scaladon.codec

import io.circe.parser.decode
import munit.FunSuite

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{
  PushAlerts,
  PushPolicy,
  PushSubscription,
  Suggestion,
  SuggestionSource
}

class PushCodecsSuite extends FunSuite:

  private val accountJson: String =
    """{
      |  "id": "109031732217496096",
      |  "username": "alice",
      |  "acct": "alice",
      |  "display_name": "Alice",
      |  "note": "",
      |  "url": "https://code.party/@alice",
      |  "avatar": "https://code.party/a.png",
      |  "header": "https://code.party/h.png",
      |  "locked": false,
      |  "created_at": "2026-02-25T11:35:01.394Z"
      |}""".stripMargin

  private val subscriptionJson: String =
    """{
      |  "id": 328183,
      |  "endpoint": "https://yourdomain.example/listener",
      |  "standard": true,
      |  "alerts": {
      |    "mention": true,
      |    "quote": true,
      |    "status": false,
      |    "reblog": true,
      |    "follow": true,
      |    "follow_request": false,
      |    "favourite": true,
      |    "poll": false,
      |    "update": true,
      |    "quoted_update": false,
      |    "admin.sign_up": true,
      |    "admin.report": false
      |  },
      |  "server_key": "BCk-key",
      |  "policy": "follower"
      |}""".stripMargin

  test("a suggestion decodes every documented source and the account") {
    val json =
      s"""{"sources":["featured","most_followed","most_interactions","similar_to_recently_followed","friends_of_friends"],"account":$accountJson}"""
    val suggestion = decode[Suggestion](json).toOption.get
    assertEquals(
      suggestion.sources,
      List(
        SuggestionSource.Featured,
        SuggestionSource.MostFollowed,
        SuggestionSource.MostInteractions,
        SuggestionSource.SimilarToRecentlyFollowed,
        SuggestionSource.FriendsOfFriends
      )
    )
    assertEquals(suggestion.account.id.value, "109031732217496096")
    assertEquals(suggestion.account.username, "alice")
  }

  test("an unknown suggestion source fails decoding") {
    val json = s"""{"sources":["mystery"],"account":$accountJson}"""
    assert(decode[Suggestion](json).isLeft)
  }

  test("a push subscription decodes alerts, server key and policy") {
    val subscription = decode[PushSubscription](subscriptionJson).toOption.get
    assertEquals(subscription.id, 328183L)
    assertEquals(subscription.endpoint, "https://yourdomain.example/listener")
    assertEquals(subscription.standard, true)
    assertEquals(subscription.serverKey, "BCk-key")
    assertEquals(subscription.policy, PushPolicy.Follower)
    assertEquals(
      subscription.alerts,
      PushAlerts(
        mention = true,
        quote = true,
        status = false,
        reblog = true,
        follow = true,
        followRequest = false,
        favourite = true,
        poll = false,
        update = true,
        quotedUpdate = false,
        adminSignUp = true,
        adminReport = false
      )
    )
  }

  test("absent alert flags, standard and policy fall back to the API defaults") {
    val json =
      """{
        |  "id": 328183,
        |  "endpoint": "https://yourdomain.example/listener",
        |  "alerts": {"mention": true},
        |  "server_key": "BCk-key"
        |}""".stripMargin
    val subscription = decode[PushSubscription](json).toOption.get
    assertEquals(subscription.standard, false)
    assertEquals(subscription.alerts, PushAlerts(mention = true))
    assertEquals(subscription.policy, PushPolicy.All)
  }

  test("an unknown push policy fails decoding") {
    val json = subscriptionJson.replace("\"follower\"", "\"no-one\"")
    assert(decode[PushSubscription](json).isLeft)
  }
