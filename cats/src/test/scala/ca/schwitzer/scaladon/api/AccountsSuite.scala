package ca.schwitzer.scaladon.api

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.time.Instant
import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{
  AccountStateError,
  ApiError,
  ApiException,
  HttpStatus,
  Id,
  Visibility
}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class AccountsSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val accountJson: String =
    """{
      |  "id": "23634",
      |  "username": "noiob",
      |  "acct": "noiob@awoo.space",
      |  "display_name": "ikea shark fan account",
      |  "locked": false,
      |  "bot": false,
      |  "group": false,
      |  "discoverable": true,
      |  "indexable": true,
      |  "noindex": false,
      |  "created_at": "2017-02-08T02:00:53.274Z",
      |  "note": "<p>:ms_rainbow_flag:</p>",
      |  "url": "https://awoo.space/@noiob",
      |  "uri": "https://awoo.space/users/noiob",
      |  "avatar": "https://files.mastodon.social/accounts/avatars/000/023/634/original/6ca8804dc46800ad.png",
      |  "avatar_static": "https://files.mastodon.social/accounts/avatars/000/023/634/original/6ca8804dc46800ad.png",
      |  "avatar_description": "a shark",
      |  "header": "https://files.mastodon.social/accounts/headers/000/023/634/original/256eb8d7ac40f49a.png",
      |  "header_static": "https://files.mastodon.social/accounts/headers/000/023/634/original/256eb8d7ac40f49a.png",
      |  "header_description": "sharks in the sea",
      |  "followers_count": 547,
      |  "following_count": 404,
      |  "statuses_count": 28468,
      |  "last_status_at": "2019-11-17",
      |  "hide_collections": false,
      |  "show_media": true,
      |  "show_media_replies": true,
      |  "show_featured": true,
      |  "fields": [
      |    { "name": "Pronouns", "value": "they/them", "verified_at": null },
      |    { "name": "Website", "value": "<a href=\"https://noiob.example\">noiob.example</a>", "verified_at": "2019-11-10T10:31:10.744+00:00" }
      |  ],
      |  "moved": {
      |    "id": "999",
      |    "username": "old",
      |    "acct": "old",
      |    "display_name": "Old",
      |    "note": "",
      |    "avatar": "https://files.mastodon.social/old.png",
      |    "header": "https://files.mastodon.social/old-header.png",
      |    "created_at": "2016-01-01T00:00:00.000Z"
      |  }
      |}""".stripMargin

  private val credentialJson: String =
    accountJson.stripSuffix("}") +
      """,
        |  "source": {
        |    "privacy": "public",
        |    "sensitive": false,
        |    "spoiler_field": false,
        |    "language": "",
        |    "note": "the raw bio",
        |    "fields": [
        |      { "name": "Website", "value": "https://noiob.example", "verified_at": null }
        |    ],
        |    "follow_requests_count": 5,
        |    "hide_collections": false,
        |    "discoverable": false,
        |    "indexable": true,
        |    "attribution_domains": ["example.com"]
        |  },
        |  "role": {
        |    "id": "-99",
        |    "name": "Owner",
        |    "permissions": "65536",
        |    "color": "",
        |    "highlighted": false
        |  },
        |  "state": {
        |    "functional": false,
        |    "confirmed": true,
        |    "approved": false,
        |    "error": "Your login is currently pending approval",
        |    "error_code": "pending_approval"
        |  }
        |}""".stripMargin

  private val relationshipJson: String =
    """{
      |  "id": "3",
      |  "following": true,
      |  "showing_reblogs": false,
      |  "notifying": false,
      |  "languages": ["en", "de"],
      |  "followed_by": false,
      |  "blocking": false,
      |  "blocked_by": false,
      |  "muting": false,
      |  "muting_notifications": false,
      |  "muting_expires_at": null,
      |  "requested": false,
      |  "requested_by": false,
      |  "domain_blocking": false,
      |  "endorsed": false,
      |  "note": ""
      |}""".stripMargin

  private val statusJson: String =
    s"""{
       |  "id": "42",
       |  "uri": "tag:code.party,2017-04-05:objectId=1",
       |  "url": "https://code.party/@noiob/42",
       |  "account": $accountJson,
       |  "content": "<p>Hello</p>",
       |  "created_at": "2017-04-05T12:00:00.000Z",
       |  "visibility": "public",
       |  "in_reply_to_id": null,
       |  "in_reply_to_account_id": null,
       |  "spoiler_text": "",
       |  "sensitive": false,
       |  "reblog": null,
       |  "reblogs_count": 0,
       |  "favourites_count": 0,
       |  "favourited": false,
       |  "reblogged": false
       |}""".stripMargin

  private val accountList: String = s"[$accountJson]"
  private val relationshipList: String = s"[$relationshipJson]"
  private val statusList: String = s"[$statusJson]"

  private def recordingBackend(response: Response): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  private def accounts(response: Response): IO[(Ref[IO, List[Request]], Accounts[IO])] =
    recordingBackend(response).map { case (ref, http) =>
      ref -> Accounts.live[IO](EndpointRunner(http, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("verifyCredentials targets the path without the 5.0 query by default and decodes source/state") {
    for
      pair       <- accounts(Response(HttpStatus.Ok, credentialJson))
      (ref, api) = pair
      credential <- api.verifyCredentials()
      requests   <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/accounts/verify_credentials")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(credential.account.id.value, "23634")
      assertEquals(credential.source.note, "the raw bio")
      assertEquals(credential.source.privacy, Visibility.Public)
      assertEquals(credential.source.followRequestsCount, 5)
      assertEquals(credential.source.attributionDomains, List("example.com"))
      assertEquals(credential.role.map(_.name), Some("Owner"))
      assertEquals(credential.state.map(_.functional), Some(false))
      assertEquals(credential.state.flatMap(_.errorCode), Some(AccountStateError.PendingApproval))
  }

  test("verifyCredentials sends allow_nonfunctional=true only when requested") {
    for
      pair     <- accounts(Response(HttpStatus.Ok, credentialJson))
      (ref, api) = pair
      _        <- api.verifyCredentials(allowNonfunctional = true)
      requests <- ref.get
    yield assertEquals(
      requests.head.url,
      "https://code.party/api/v1/accounts/verify_credentials?allow_nonfunctional=true"
    )
  }

  test("fetch is public, decodes the current account shape and recurses into moved") {
    for
      pair     <- accounts(Response(HttpStatus.Ok, accountJson))
      (ref, api) = pair
      account  <- api.fetch(Id("23634"))
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/accounts/23634")
      assert(!request.headers.contains("Authorization"), "fetch is a public endpoint")
      assertEquals(account.bot, false)
      assertEquals(account.group, false)
      assertEquals(account.indexable, true)
      assertEquals(account.discoverable, Some(true))
      assertEquals(account.showMedia, true)
      assertEquals(account.statusesCount, 28468)
      assertEquals(account.lastStatusAt, Some(Instant.parse("2019-11-17T00:00:00Z")))
      assertEquals(account.fields.map(_.name), List("Pronouns", "Website"))
      assertEquals(account.fields(1).verifiedAt, Some(Instant.parse("2019-11-10T10:31:10.744Z")))
      assertEquals(account.moved.map(_.id.value), Some("999"))
  }

  test("statuses encodes pagination and every exclude flag") {
    for
      pair     <- accounts(Response(HttpStatus.Ok, statusList))
      (ref, api) = pair
      statuses <- api.statuses(
                    Id("23634"),
                    maxId = Some(Id("100")),
                    sinceId = Some(Id("50")),
                    minId = Some(Id("10")),
                    limit = Some(5),
                    onlyMedia = Some(true),
                    excludeReplies = Some(true),
                    excludeReblogs = Some(true),
                    pinned = Some(true),
                    tagged = Some("scala")
                  )
      requests <- ref.get
    yield
      assertEquals(statuses.map(_.id.value), List("42"))
      assertEquals(
        requests.head.url,
        "https://code.party/api/v1/accounts/23634/statuses" +
          "?max_id=100&since_id=50&min_id=10&limit=5" +
          "&only_media=true&exclude_replies=true&exclude_reblogs=true&pinned=true&tagged=scala"
      )
      assert(!requests.head.headers.contains("Authorization"))
  }

  test("followers and following page through accounts") {
    for
      pair       <- accounts(Response(HttpStatus.Ok, accountList))
      (ref, api) = pair
      followers  <- api.followers(Id("1"), limit = Some(2), maxId = Some(Id("9")))
      following  <- api.following(Id("1"), sinceId = Some(Id("7")))
      requests   <- ref.get
    yield
      assertEquals(followers.map(_.id.value), List("23634"))
      assertEquals(following.map(_.id.value), List("23634"))
      assertEquals(
        requests(0).url,
        "https://code.party/api/v1/accounts/1/followers?max_id=9&limit=2"
      )
      assertEquals(
        requests(1).url,
        "https://code.party/api/v1/accounts/1/following?since_id=7"
      )
  }

  test("follow posts optional form fields, including repeated languages[]") {
    for
      pair         <- accounts(Response(HttpStatus.Ok, relationshipJson))
      (ref, api)   = pair
      relationship <- api.follow(
                        Id("3"),
                        reblogs = Some(false),
                        notify = Some(true),
                        languages = List("en", "de")
                      )
      requests     <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/accounts/3/follow")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(
        request.form,
        Some(
          List(
            "reblogs"     -> "false",
            "notify"      -> "true",
            "languages[]" -> "en",
            "languages[]" -> "de"
          )
        )
      )
      assertEquals(relationship.following, true)
      assertEquals(relationship.languages, Some(List("en", "de")))
  }

  test("unfollow, block, unblock and unmute post to their action paths") {
    for
      pair     <- accounts(Response(HttpStatus.Ok, relationshipJson))
      (ref, api) = pair
      _        <- api.unfollow(Id("3"))
      _        <- api.block(Id("3"))
      _        <- api.unblock(Id("3"))
      _        <- api.unmute(Id("3"))
      requests <- ref.get
    yield
      assertEquals(
        requests.map(_.url),
        List(
          "https://code.party/api/v1/accounts/3/unfollow",
          "https://code.party/api/v1/accounts/3/block",
          "https://code.party/api/v1/accounts/3/unblock",
          "https://code.party/api/v1/accounts/3/unmute"
        )
      )
      assert(requests.forall(_.method == HttpMethod.POST))
  }

  test("mute encodes notifications and duration in seconds") {
    for
      pair     <- accounts(Response(HttpStatus.Ok, relationshipJson))
      (ref, api) = pair
      _        <- api.mute(Id("3"), notifications = Some(false), duration = Some(10.minutes))
      requests <- ref.get
    yield assertEquals(
      requests.head.form,
      Some(List("notifications" -> "false", "duration" -> "600"))
    )
  }

  test("relationships repeats id[] and decodes every relationship") {
    for
      pair          <- accounts(Response(HttpStatus.Ok, relationshipList))
      (ref, api)    = pair
      relationships <- api.relationships(List(Id("1"), Id("2")))
      requests      <- ref.get
    yield
      assertEquals(relationships.map(_.id.value), List("3"))
      assertEquals(
        Accounts.relationshipsEndpoint(List(Id("1"), Id("2"))).query,
        List("id[]" -> "1", "id[]" -> "2")
      )
      assert(requests.head.url.contains("id%5B%5D=1"), requests.head.url)
      assert(requests.head.url.contains("id%5B%5D=2"), requests.head.url)
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("a 404 raises a structured NotFound error") {
    for
      pair   <- accounts(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      result <- pair._2.fetch(Id("42")).attempt
    yield assertEquals(apiError(result).isNotFound, true)
  }

  test("a 401 is identifiable as an authorization failure") {
    for
      pair   <- accounts(Response(HttpStatus.Unauthorized, """{"error":"The access token is invalid"}"""))
      result <- pair._2.relationships(List(Id("3"))).attempt
    yield assertEquals(apiError(result).isUnauthorized, true)
  }

  test("a 429 becomes RateLimited with Retry-After") {
    val response = Response(
      HttpStatus(429),
      """{"error":"Too many requests"}""",
      Map("Retry-After" -> "5")
    )
    for
      pair   <- accounts(response)
      result <- pair._2.fetch(Id("42")).attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.RateLimited(Some(5.seconds), Some("Too many requests")))
      assertEquals(error.retryable, true)
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair   <- accounts(Response(HttpStatus.Ok, """{"id":"42"}"""))
      result <- pair._2.fetch(Id("42")).attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], apiError(result).toString)
  }

  test("an anonymous client fails authenticated calls without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      http = HttpBackend.of[IO](_ => calls.update(_ + 1).as(Response(HttpStatus.Ok, credentialJson)))
      api = Accounts.live[IO](EndpointRunner.anonymous(http, host))
      result <- api.verifyCredentials().attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/accounts/verify_credentials"))
      assertEquals(sent, 0)
  }

  test("mapK translates the accounts algebra between effects") {
    val identity = new cats.~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair   <- accounts(Response(HttpStatus.Ok, accountJson))
      result <- pair._2.mapK(identity).fetch(Id("23634")).attempt
    yield assertEquals(result.map(_.id.value), Right("23634"))
  }
