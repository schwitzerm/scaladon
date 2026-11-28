package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class NotificationsSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val accountJson: String =
    """{
      |  "id": "971724",
      |  "username": "zsc",
      |  "acct": "zsc",
      |  "display_name": "ZSC",
      |  "note": "",
      |  "url": "https://code.party/@zsc",
      |  "avatar": "https://code.party/z.png",
      |  "header": "https://code.party/zh.png",
      |  "locked": false,
      |  "created_at": "2019-11-23T07:00:00.000Z"
      |}""".stripMargin

  private val statusJson: String =
    s"""{
       |  "id": "103186126728896492",
       |  "uri": "https://code.party/users/zsc/statuses/103186126728896492",
       |  "url": "https://code.party/@zsc/103186126728896492",
       |  "account": $accountJson,
       |  "content": "<p>sup!</p>",
       |  "created_at": "2019-11-23T07:49:01.940Z",
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

  private val mentionJson: String =
    s"""{
       |  "id": "34975861",
       |  "type": "mention",
       |  "group_key": "ungrouped-34975861",
       |  "created_at": "2019-11-23T07:49:02.064Z",
       |  "account": $accountJson,
       |  "status": $statusJson
       |}""".stripMargin

  private val followJson: String =
    s"""{
       |  "id": "34975862",
       |  "type": "follow",
       |  "created_at": "2019-11-23T07:50:00.000Z",
       |  "account": $accountJson
       |}""".stripMargin

  private val collectionJson: String =
    """{
      |  "id": "116131056935959117",
      |  "account_id": "113668893442515793",
      |  "uri": "https://example.com/ap/113668893442515793/collections/116131056935959117",
      |  "url": "https://example.com/collections/116131056935959117",
      |  "name": "Excellent people",
      |  "description": "Well worth following",
      |  "language": "en",
      |  "local": true,
      |  "sensitive": false,
      |  "discoverable": true,
      |  "tag": {
      |    "name": "discovery",
      |    "url": "https://example.com/tags/discovery"
      |  },
      |  "item_count": 0,
      |  "items": [],
      |  "created_at": "2026-02-25T11:35:01.394Z",
      |  "updated_at": "2026-02-25T11:35:01.394Z"
      |}""".stripMargin

  private def recording(
      response: Response
  ): IO[(Ref[IO, List[Request]], Notifications[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Notifications.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("list repeats the bracketed array parameters and decodes every notification") {
    val payload = s"[$mentionJson,$followJson]"
    for
      pair      <- recording(Response(HttpStatus.Ok, payload))
      (ref, api) = pair
      result <- api.list(
                  types = List("mention", "favourite"),
                  excludeTypes = List("reblog"),
                  accountId = Some(Id("971724")),
                  supportedTypes = List("mention"),
                  limit = Some(2),
                  maxId = Some(Id("100")),
                  sinceId = Some(Id("50")),
                  minId = Some(Id("60"))
                )
      requests <- ref.get
    yield
      assertEquals(result.map(_.kind), List("mention", "follow"))
      assertEquals(result.head.status.map(_.id.value), Some("103186126728896492"))
      assertEquals(result(1).status, None)
      assertEquals(result(1).groupKey, None)
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(
        request.url,
        "https://code.party/api/v1/notifications" +
          "?types%5B%5D=mention&types%5B%5D=favourite" +
          "&exclude_types%5B%5D=reblog" +
          "&account_id=971724" +
          "&supported_types%5B%5D=mention" +
          "&limit=2&max_id=100&since_id=50&min_id=60"
      )
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("list with no filters has no query string") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "[]"))
      (ref, api) = pair
      result    <- api.list()
      requests  <- ref.get
    yield
      assertEquals(result, Nil)
      assertEquals(requests.head.url, "https://code.party/api/v1/notifications")
  }

  test("get targets the notification path and decodes its status") {
    for
      pair      <- recording(Response(HttpStatus.Ok, mentionJson))
      (ref, api) = pair
      result    <- api.get(Id("34975861"))
      requests  <- ref.get
    yield
      assertEquals(result.id.value, "34975861")
      assertEquals(result.kind, "mention")
      assertEquals(result.account.username, "zsc")
      assertEquals(result.status.map(_.content), Some("<p>sup!</p>"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/notifications/34975861")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("clear posts to the clear path with no body") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.clear().attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/notifications/clear")
      assertEquals(request.body, None)
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("a fallback is decoded together with the collection that caused it") {
    val payload =
      s"""{
         |  "id": "40000001",
         |  "type": "added_to_collection",
         |  "created_at": "2026-02-25T11:35:01.394Z",
         |  "account": $accountJson,
         |  "collection": $collectionJson,
         |  "fallback": {
         |    "title": "ZSC added you to a collection",
         |    "summary": "You are on an app that does not support this notification type.",
         |    "details": null
         |  }
         |}""".stripMargin
    for
      pair      <- recording(Response(HttpStatus.Ok, payload))
      (_, api)   = pair
      result    <- api.get(Id("40000001"))
    yield
      assertEquals(result.kind, "added_to_collection")
      assertEquals(result.collection.map(_.name), Some("Excellent people"))
      assertEquals(result.fallback.map(_.title), Some("ZSC added you to a collection"))
      assertEquals(result.fallback.flatMap(_.details), None)
  }

  test("an unknown notification kind still decodes and leans on its fallback") {
    val payload =
      s"""{
         |  "id": "40000002",
         |  "type": "some_future_kind",
         |  "created_at": "2026-02-25T11:35:01.394Z",
         |  "account": $accountJson,
         |  "fallback": {
         |    "title": "Something new happened",
         |    "summary": null,
         |    "details": "Open the web app for the full story."
         |  }
         |}""".stripMargin
    for
      pair      <- recording(Response(HttpStatus.Ok, payload))
      (_, api)   = pair
      result    <- api.get(Id("40000002"))
    yield
      assertEquals(result.kind, "some_future_kind")
      assertEquals(result.fallback.map(_.title), Some("Something new happened"))
      assertEquals(result.fallback.flatMap(_.details), Some("Open the web app for the full story."))
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair      <- recording(Response(HttpStatus.Ok, """{"notifications":[]}"""))
      (_, api)   = pair
      result    <- api.list().attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("a 429 becomes RateLimited with Retry-After and is retryable") {
    for
      pair <- recording(
                Response(HttpStatus(429), """{"error":"Too many requests"}""", Map("Retry-After" -> "5"))
              )
      (_, api) = pair
      result   <- api.get(Id("34975861")).attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.RateLimited(Some(5.seconds), Some("Too many requests")))
      assertEquals(error.retryable, true)
  }

  test("an anonymous client fails clear without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ => calls.update(_ + 1).as(Response(HttpStatus.Ok, "{}")))
      api     = Notifications.live[IO](EndpointRunner.anonymous(backend, host))
      result <- api.clear().attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/notifications/clear"))
      assertEquals(sent, 0)
  }

  private val groupedJson: String =
    s"""{
       |  "accounts": [$accountJson],
       |  "statuses": [$statusJson],
       |  "notification_groups": [
       |    {
       |      "group_key": "favourite-103186126728896492-479000",
       |      "notifications_count": 2,
       |      "type": "favourite",
       |      "most_recent_notification_id": "196014",
       |      "page_min_id": "196013",
       |      "page_max_id": "196014",
       |      "latest_page_notification_at": "2024-08-23T08:59:56.743Z",
       |      "sample_account_ids": ["971724"],
       |      "status_id": "103186126728896492"
       |    }
       |  ]
       |}""".stripMargin

  private val singleGroupJson: String =
    s"""{
       |  "accounts": [$accountJson],
       |  "statuses": [],
       |  "notification_groups": [
       |    {
       |      "group_key": "added_to_collection-116131056935959117-479001",
       |      "notifications_count": 1,
       |      "type": "added_to_collection",
       |      "most_recent_notification_id": "40000001",
       |      "sample_account_ids": ["971724"],
       |      "collection": $collectionJson,
       |      "fallback": {
       |        "title": "ZSC added you to a collection",
       |        "summary": null,
       |        "details": "Open the web app for the full story."
       |      }
       |    }
       |  ]
       |}""".stripMargin

  test("get passes supported_types so the server can attach a fallback") {
    for
      pair      <- recording(Response(HttpStatus.Ok, mentionJson))
      (ref, api) = pair
      result    <- api.get(Id("34975861"), supportedTypes = List("mention", "favourite"))
      requests  <- ref.get
    yield
      assertEquals(result.id.value, "34975861")
      assertEquals(
        requests.head.url,
        "https://code.party/api/v1/notifications/34975861" +
          "?supported_types%5B%5D=mention&supported_types%5B%5D=favourite"
      )
  }

  test("grouped repeats the v2 array filters and decodes the hoisted entities") {
    for
      pair      <- recording(Response(HttpStatus.Ok, groupedJson))
      (ref, api) = pair
      result    <- api.grouped(
                     types = List("favourite", "follow"),
                     excludeTypes = List("reblog"),
                     supportedTypes = List("favourite"),
                     limit = Some(20)
                   )
      requests  <- ref.get
    yield
      assertEquals(result.accounts.map(_.username), List("zsc"))
      assertEquals(result.statuses.map(_.id.value), List("103186126728896492"))
      val group = result.notificationGroups.head
      assertEquals(group.groupKey, "favourite-103186126728896492-479000")
      assertEquals(group.notificationsCount, 2)
      assertEquals(group.kind, "favourite")
      assertEquals(group.mostRecentNotificationId.value, "196014")
      assertEquals(group.pageMinId.map(_.value), Some("196013"))
      assertEquals(group.pageMaxId.map(_.value), Some("196014"))
      assertEquals(group.latestPageNotificationAt.map(_.toString), Some("2024-08-23T08:59:56.743Z"))
      assertEquals(group.sampleAccountIds.map(_.value), List("971724"))
      assertEquals(group.statusId.map(_.value), Some("103186126728896492"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(
        request.url,
        "https://code.party/api/v2/notifications" +
          "?types%5B%5D=favourite&types%5B%5D=follow" +
          "&exclude_types%5B%5D=reblog" +
          "&supported_types%5B%5D=favourite" +
          "&limit=20"
      )
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("group decodes page bounds, collection and fallback and passes supported_types") {
    for
      pair     <- recording(Response(HttpStatus.Ok, singleGroupJson))
      (ref, api) = pair
      result   <- api.group(
                    "added_to_collection-116131056935959117-479001",
                    supportedTypes = List("mention")
                  )
      requests <- ref.get
    yield
      val group = result.notificationGroups.head
      assertEquals(group.kind, "added_to_collection")
      assertEquals(group.pageMinId, None)
      assertEquals(group.collection.map(_.name), Some("Excellent people"))
      assertEquals(group.fallback.map(_.title), Some("ZSC added you to a collection"))
      assertEquals(group.fallback.flatMap(_.details), Some("Open the web app for the full story."))
      assertEquals(
        requests.head.url,
        "https://code.party/api/v2/notifications" +
          "/added_to_collection-116131056935959117-479001?supported_types%5B%5D=mention"
      )
  }

  test("dismissGroup posts to the group dismiss path with no body") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.dismissGroup("favourite-103186126728896492-479000").attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(
        request.url,
        "https://code.party/api/v2/notifications/favourite-103186126728896492-479000/dismiss"
      )
      assertEquals(request.body, None)
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("unreadCount reads the capped v2 count") {
    for
      pair      <- recording(Response(HttpStatus.Ok, """{"count":42}"""))
      (ref, api) = pair
      result    <- api.unreadCount()
      requests  <- ref.get
    yield
      assertEquals(result.count, 42)
      assertEquals(requests.head.method, HttpMethod.GET)
      assertEquals(requests.head.url, "https://code.party/api/v2/notifications/unread_count")
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("a v2 429 becomes RateLimited with Retry-After and is retryable") {
    for
      pair <- recording(
                Response(HttpStatus(429), """{"error":"Too many requests"}""", Map("Retry-After" -> "5"))
              )
      (_, api) = pair
      result   <- api.grouped().attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.RateLimited(Some(5.seconds), Some("Too many requests")))
      assertEquals(error.retryable, true)
  }

  test("an anonymous client fails a v2 call without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ => calls.update(_ + 1).as(Response(HttpStatus.Ok, "{}")))
      api     = Notifications.live[IO](EndpointRunner.anonymous(backend, host))
      result <- api.unreadCount().attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v2/notifications/unread_count"))
      assertEquals(sent, 0)
  }

  test("a malformed v2 group body becomes a Decode error") {
    for
      pair      <- recording(Response(HttpStatus.Ok, """{"notification_groups":[]}"""))
      (_, api)   = pair
      result    <- api.grouped().attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair      <- recording(Response(HttpStatus.Ok, mentionJson))
      (_, api)   = pair
      translated = api.mapK(identity)
      result    <- translated.get(Id("34975861")).attempt
    yield assertEquals(result.map(_.kind), Right("mention"))
  }
