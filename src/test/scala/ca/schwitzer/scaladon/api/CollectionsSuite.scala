package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, CollectionItemState, HttpStatus, Id}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class CollectionsSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val accountJson: String =
    """{
      |  "id": "113668893442515793",
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
      |  "item_count": 2,
      |  "items": [
      |    {
      |      "id": "116141056635954112",
      |      "account_id": "112658193342215767",
      |      "state": "accepted",
      |      "created_at": "2026-02-25T11:35:01.394Z"
      |    },
      |    {
      |      "id": "116141051635254139",
      |      "account_id": "117628196343117789",
      |      "state": "pending",
      |      "created_at": "2026-02-25T11:35:01.394Z"
      |    }
      |  ],
      |  "created_at": "2026-02-25T11:35:01.394Z",
      |  "updated_at": "2026-02-25T11:37:38.182Z"
      |}""".stripMargin

  private def wrapped(json: String): String = s"""{"collection":$json}"""

  private def wrappedItem(state: String): String =
    s"""{"collection_item":{"id":"116151066915353115","account_id":"112658193342215767","state":"$state","created_at":"2026-02-25T11:35:01.394Z"}}"""

  private def recording(
      response: Response
  ): IO[(Ref[IO, List[Request]], Collections[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Collections.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("create posts the required name and the account_ids array, omitting absent options") {
    for
      pair      <- recording(Response(HttpStatus.Ok, wrapped(collectionJson)))
      (ref, api) = pair
      result    <- api.create("Excellent people", accountIds = List(Id("1"), Id("2")))
      requests  <- ref.get
    yield
      assertEquals(result.name, "Excellent people")
      assertEquals(result.itemCount, 2)
      assertEquals(result.items.map(_.state), List(CollectionItemState.Accepted, CollectionItemState.Pending))
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/collections")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      val body = request.body.flatMap(_.asObject).get
      assertEquals(body("name").flatMap(_.asString), Some("Excellent people"))
      assertEquals(
        body("account_ids").flatMap(_.asArray).map(_.flatMap(_.asString).toList),
        Some(List("1", "2"))
      )
      assert(!body.contains("description"), "absent description must be omitted")
      assert(!body.contains("sensitive"), "absent sensitive must be omitted")
  }

  test("create sends every option when it is present") {
    for
      pair      <- recording(Response(HttpStatus.Ok, wrapped(collectionJson)))
      (ref, api) = pair
      _ <- api.create(
             "Excellent people",
             description = Some("Well worth following"),
             language = Some("en"),
             tagName = Some("#discovery"),
             sensitive = Some(false),
             discoverable = Some(true)
           )
      requests <- ref.get
    yield
      val body = requests.head.body.flatMap(_.asObject).get
      assertEquals(body("description").flatMap(_.asString), Some("Well worth following"))
      assertEquals(body("language").flatMap(_.asString), Some("en"))
      assertEquals(body("tag_name").flatMap(_.asString), Some("#discovery"))
      assertEquals(body("sensitive").flatMap(_.asBoolean), Some(false))
      assertEquals(body("discoverable").flatMap(_.asBoolean), Some(true))
      assert(!body.contains("account_ids"), "empty account_ids must be omitted")
  }

  test("update PATCHes only the options supplied") {
    for
      pair      <- recording(Response(HttpStatus.Ok, wrapped(collectionJson)))
      (ref, api) = pair
      result    <- api.update(Id("116131056935959117"), name = Some("Renamed"))
      requests  <- ref.get
    yield
      assertEquals(result.name, "Excellent people")
      val request = requests.head
      assertEquals(request.method, HttpMethod.PATCH)
      assertEquals(request.url, "https://code.party/api/v1/collections/116131056935959117")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      val body = request.body.flatMap(_.asObject).get
      assertEquals(body("name").flatMap(_.asString), Some("Renamed"))
      assertEquals(body.toList.map(_._1), List("name"))
  }

  test("get decodes the collection together with its owner and featured accounts") {
    val payload =
      s"""{"accounts":[$accountJson],"collection":$collectionJson}"""
    for
      pair      <- recording(Response(HttpStatus.Ok, payload))
      (ref, api) = pair
      result    <- api.get(Id("116131056935959117"))
      requests  <- ref.get
    yield
      assertEquals(result.collection.id.value, "116131056935959117")
      assertEquals(result.collection.accountId.value, "113668893442515793")
      assertEquals(result.collection.tag.map(_.name), Some("discovery"))
      assertEquals(result.collection.items.head.accountId.map(_.value), Some("112658193342215767"))
      assertEquals(result.accounts.map(_.username), List("alice"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/collections/116131056935959117")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("delete targets the collection path") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.delete(Id("116131056935959117")).attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      val request = requests.head
      assertEquals(request.method, HttpMethod.DELETE)
      assertEquals(request.url, "https://code.party/api/v1/collections/116131056935959117")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("addItem posts the account id and decodes the wrapped item state") {
    for
      pair      <- recording(Response(HttpStatus.Ok, wrappedItem("accepted")))
      (ref, api) = pair
      result    <- api.addItem(Id("116131056935959117"), Id("112658193342215767"))
      requests  <- ref.get
    yield
      assertEquals(result.state, CollectionItemState.Accepted)
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(
        request.url,
        "https://code.party/api/v1/collections/116131056935959117/items"
      )
      val body = request.body.flatMap(_.asObject).get
      assertEquals(body("account_id").flatMap(_.asString), Some("112658193342215767"))
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("removeItem and revokeItem hit their distinct paths") {
    for
      removedPair <- recording(Response(HttpStatus.Ok, "{}"))
      (removedRef, removed) = removedPair
      _          <- removed.removeItem(Id("116131056935959117"), Id("116151066915353115"))
      removedReq <- removedRef.get
      revokedPair <- recording(Response(HttpStatus.Ok, "{}"))
      (revokedRef, revoked) = revokedPair
      _          <- revoked.revokeItem(Id("116131056935959117"), Id("116151066915353115"))
      revokedReq <- revokedRef.get
    yield
      assertEquals(removedReq.head.method, HttpMethod.DELETE)
      assertEquals(
        removedReq.head.url,
        "https://code.party/api/v1/collections/116131056935959117/items/116151066915353115"
      )
      assertEquals(revokedReq.head.method, HttpMethod.POST)
      assertEquals(
        revokedReq.head.url,
        "https://code.party/api/v1/collections/116131056935959117/items/116151066915353115/revoke"
      )
  }

  test("forAccount lists the account's collections with limit and offset") {
    val payload = s"""{"collections":[$collectionJson]}"""
    for
      pair      <- recording(Response(HttpStatus.Ok, payload))
      (ref, api) = pair
      result    <- api.forAccount(Id("113668893442515793"), limit = Some(20), offset = Some(40))
      requests  <- ref.get
    yield
      assertEquals(result.map(_.name), List("Excellent people"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(
        request.url,
        "https://code.party/api/v1/accounts/113668893442515793/collections?limit=20&offset=40"
      )
  }

  test("inCollections lists the collections featuring the account without an empty query string") {
    val payload = s"""{"collections":[$collectionJson]}"""
    for
      pair      <- recording(Response(HttpStatus.Ok, payload))
      (ref, api) = pair
      result    <- api.inCollections(Id("112658193342215767"))
      requests  <- ref.get
    yield
      assertEquals(result.map(_.id.value), List("116131056935959117"))
      assertEquals(
        requests.head.url,
        "https://code.party/api/v1/accounts/112658193342215767/in_collections"
      )
  }

  test("an unknown collection item state fails decoding instead of leaking a string") {
    val unknown =
      s"""{"collection":${collectionJson.replace("\"state\": \"accepted\"", "\"state\": \"mystery\"")}}"""
    for
      pair      <- recording(Response(HttpStatus.Ok, unknown))
      (_, api)   = pair
      result    <- api.create("Excellent people").attempt
    yield
      val error = apiError(result)
      assert(error.isInstanceOf[ApiError.Decode], s"got $error")
  }

  test("a 404 raises a structured, non-retryable Http error") {
    for
      pair      <- recording(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, api)   = pair
      result    <- api.get(Id("42")).attempt
    yield
      val error = apiError(result)
      assert(error.isNotFound, s"expected isNotFound, got $error")
      assertEquals(error.retryable, false)
  }

  test("a 429 becomes RateLimited with Retry-After") {
    for
      pair <- recording(
                Response(HttpStatus(429), """{"error":"Too many requests"}""", Map("Retry-After" -> "5"))
              )
      (_, api) = pair
      result   <- api.delete(Id("42")).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(5.seconds), Some("Too many requests"))
      )
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair      <- recording(Response(HttpStatus.Ok, """{"collection":{"id":"42"}}"""))
      (_, api)   = pair
      result    <- api.get(Id("42")).attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("an anonymous client fails authenticated collection calls without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, wrapped(collectionJson)))
                )
      api = Collections.live[IO](EndpointRunner.anonymous(backend, host))
      result <- api.create("Excellent people").attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/collections"))
      assertEquals(sent, 0)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    val payload = s"""{"accounts":[$accountJson],"collection":$collectionJson}"""
    for
      pair      <- recording(Response(HttpStatus.Ok, payload))
      (_, api)   = pair
      translated = api.mapK(identity)
      result    <- translated.get(Id("116131056935959117")).attempt
    yield assertEquals(result.map(_.collection.name), Right("Excellent people"))
  }
