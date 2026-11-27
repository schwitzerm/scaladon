package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id, RepliesPolicy}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class ListsSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val listJson: String =
    """{
      |  "id": "12249",
      |  "title": "Friends",
      |  "replies_policy": "followed",
      |  "exclusive": false
      |}""".stripMargin

  private val otherListJson: String =
    """{
      |  "id": "13585",
      |  "title": "test",
      |  "replies_policy": "list",
      |  "exclusive": true
      |}""".stripMargin

  private def accountJson(username: String): String =
    s"""{
       |  "id": "952529",
       |  "username": "$username",
       |  "acct": "$username@code.party",
       |  "display_name": "$username",
       |  "note": "",
       |  "url": "https://code.party/@$username",
       |  "avatar": "https://code.party/a.png",
       |  "header": "https://code.party/h.png",
       |  "locked": false,
       |  "created_at": "2026-02-25T11:35:01.394Z"
       |}""".stripMargin

  private def recording(response: Response): IO[(Ref[IO, List[Request]], Lists[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Lists.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("list fetches every owned list and decodes the replies policy") {
    for
      pair      <- recording(Response(HttpStatus.Ok, s"[$listJson,$otherListJson]"))
      (ref, api) = pair
      result    <- api.list()
      requests  <- ref.get
    yield
      assertEquals(result.map(_.title), List("Friends", "test"))
      assertEquals(result.map(_.repliesPolicy), List(RepliesPolicy.Followed, RepliesPolicy.ListMembers))
      assertEquals(result.map(_.exclusive), List(false, true))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/lists")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(request.body, None)
      assertEquals(request.form, None)
  }

  test("create posts title as a form and omits unsupplied options") {
    for
      pair      <- recording(Response(HttpStatus.Ok, listJson))
      (ref, api) = pair
      result    <- api.create("Friends")
      requests  <- ref.get
    yield
      assertEquals(result.title, "Friends")
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/lists")
      assertEquals(request.body, None)
      assertEquals(request.form, Some(List("title" -> "Friends")))
  }

  test("create sends replies_policy and exclusive when supplied") {
    for
      pair      <- recording(Response(HttpStatus.Ok, listJson))
      (ref, api) = pair
      _ <- api.create(
             "Quiet",
             repliesPolicy = Some(RepliesPolicy.NoReplies),
             exclusive = Some(true)
           )
      requests <- ref.get
    yield
      assertEquals(
        requests.head.form,
        Some(
          List(
            "title"          -> "Quiet",
            "replies_policy" -> "none",
            "exclusive"      -> "true"
          )
        )
      )
  }

  test("get targets the single list path") {
    for
      pair      <- recording(Response(HttpStatus.Ok, listJson))
      (ref, api) = pair
      result    <- api.get(Id("12249"))
      requests  <- ref.get
    yield
      assertEquals(result.id.value, "12249")
      assertEquals(requests.head.method, HttpMethod.GET)
      assertEquals(requests.head.url, "https://code.party/api/v1/lists/12249")
  }

  test("update PUTs the full form and keeps the server's title") {
    for
      pair      <- recording(Response(HttpStatus.Ok, listJson))
      (ref, api) = pair
      result    <- api.update(Id("12249"), "Best friends", repliesPolicy = Some(RepliesPolicy.ListMembers))
      requests  <- ref.get
    yield
      assertEquals(result.title, "Friends")
      val request = requests.head
      assertEquals(request.method, HttpMethod.PUT)
      assertEquals(request.url, "https://code.party/api/v1/lists/12249")
      assertEquals(request.body, None)
      assertEquals(
        request.form,
        Some(List("title" -> "Best friends", "replies_policy" -> "list"))
      )
  }

  test("delete targets the list path and accepts an empty object") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.delete(Id("12249")).attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      assertEquals(requests.head.method, HttpMethod.DELETE)
      assertEquals(requests.head.url, "https://code.party/api/v1/lists/12249")
  }

  test("accounts lists members with the cursor query, including limit=0") {
    for
      pair      <- recording(
                     Response(HttpStatus.Ok, s"[${accountJson("alayna")},${accountJson("cole")}]")
                   )
      (ref, api) = pair
      result <- api.accounts(Id("12249"), maxId = Some(Id("99")), sinceId = Some(Id("11")), limit = Some(0))
      requests <- ref.get
    yield
      assertEquals(result.map(_.username), List("alayna", "cole"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(
        request.url,
        "https://code.party/api/v1/lists/12249/accounts?max_id=99&since_id=11&limit=0"
      )
  }

  test("accounts without pagination sends no query string") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "[]"))
      (ref, api) = pair
      _         <- api.accounts(Id("12249"))
      requests  <- ref.get
    yield assertEquals(requests.head.url, "https://code.party/api/v1/lists/12249/accounts")
  }

  test("addAccounts posts repeated account_ids[] keys and accepts an empty object") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.addAccounts(Id("12249"), List(Id("952529"), Id("917388"))).attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/lists/12249/accounts")
      assertEquals(
        request.form,
        Some(List("account_ids[]" -> "952529", "account_ids[]" -> "917388"))
      )
  }

  test("removeAccounts DELETEs with a repeated account_ids[] form body") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.removeAccounts(Id("12249"), List(Id("952529"))).attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      val request = requests.head
      assertEquals(request.method, HttpMethod.DELETE)
      assertEquals(request.url, "https://code.party/api/v1/lists/12249/accounts")
      assertEquals(request.form, Some(List("account_ids[]" -> "952529")))
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

  test("a 422 keeps the validation message") {
    for
      pair <- recording(
                Response(HttpStatus(422), """{"error":"Validation failed: Account has already been taken"}""")
              )
      (_, api) = pair
      result   <- api.addAccounts(Id("12249"), List(Id("952529"))).attempt
    yield
      val error = apiError(result)
      assertEquals(
        error,
        ApiError.Http(
          HttpStatus(422),
          Some("Validation failed: Account has already been taken"),
          """{"error":"Validation failed: Account has already been taken"}"""
        )
      )
      assert(!error.retryable)
  }

  test("a 429 becomes RateLimited with Retry-After") {
    for
      pair <- recording(
                Response(HttpStatus(429), """{"error":"Too many requests"}""", Map("Retry-After" -> "5"))
              )
      (_, api) = pair
      result   <- api.addAccounts(Id("12249"), List(Id("952529"))).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(5.seconds), Some("Too many requests"))
      )
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair      <- recording(Response(HttpStatus.Ok, """{"id":"12249"}"""))
      (_, api)   = pair
      result    <- api.list().attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("an anonymous client fails authenticated list calls without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, "[]"))
                )
      api    <- IO.pure(Lists.live[IO](EndpointRunner.anonymous(backend, host)))
      result <- api.list().attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/lists"))
      assertEquals(sent, 0)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair      <- recording(Response(HttpStatus.Ok, listJson))
      (_, api)   = pair
      translated = api.mapK(identity)
      result    <- translated.get(Id("12249")).attempt
    yield assertEquals(result.map(_.title), Right("Friends"))
  }
