package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{
  ApiError,
  ApiException,
  FilterAction,
  FilterContext,
  FilterKeyword,
  HttpStatus,
  Id
}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class FiltersSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val filterJson: String =
    """{
      |  "id": "20060",
      |  "title": "Remove Twitter crossposts from public timeline",
      |  "context": ["public"],
      |  "expires_at": null,
      |  "filter_action": "hide",
      |  "keywords": [
      |    { "id": "1311", "keyword": "from birdsite", "whole_word": true },
      |    { "id": "1324", "keyword": "@twitter.com", "whole_word": false }
      |  ],
      |  "statuses": [
      |    { "id": "897", "status_id": "109416512469928632" }
      |  ]
      |}""".stripMargin

  private val keywordJson: String =
    """{
      |  "id": "34979",
      |  "keyword": "baz",
      |  "whole_word": true
      |}""".stripMargin

  private val statusJson: String =
    """{
      |  "id": "897",
      |  "status_id": "109416512469928632"
      |}""".stripMargin

  private def recording(
      response: Response
  ): IO[(Ref[IO, List[Request]], Filters[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Filters.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("list GETs /api/v2/filters and decodes the array") {
    for
      pair      <- recording(Response(HttpStatus.Ok, s"[$filterJson]"))
      (ref, api) = pair
      result    <- api.list()
      requests  <- ref.get
    yield
      assertEquals(result.map(_.id.value), List("20060"))
      assertEquals(result.head.context, List(FilterContext.Public))
      assertEquals(result.head.filterAction, FilterAction.Hide)
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v2/filters")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("get targets one filter and a 404 is a non-retryable Http error") {
    for
      pair    <- recording(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, api) = pair
      result  <- api.get(Id("20060")).attempt
    yield
      val error = apiError(result)
      assert(error.isNotFound, s"expected isNotFound, got $error")
      assertEquals(error.retryable, false)
  }

  test("get decodes keywords and statuses of the filter group") {
    for
      pair      <- recording(Response(HttpStatus.Ok, filterJson))
      (ref, api) = pair
      result    <- api.get(Id("20060"))
      requests  <- ref.get
    yield
      assertEquals(result.title, "Remove Twitter crossposts from public timeline")
      assertEquals(result.expiresAt, None)
      assertEquals(result.keywords.map(_.keyword), List("from birdsite", "@twitter.com"))
      assertEquals(result.keywords.map(_.wholeWord), List(true, false))
      assertEquals(result.statuses.map(_.statusId.value), List("109416512469928632"))
      assertEquals(requests.head.url, "https://code.party/api/v2/filters/20060")
  }

  test("create POSTs a form with repeated context[] and ordered keyword attributes") {
    for
      pair      <- recording(Response(HttpStatus.Ok, filterJson))
      (ref, api) = pair
      result <- api.create(
                  "Test filter",
                  List(FilterContext.Home, FilterContext.Public),
                  filterAction = Some(FilterAction.Warn),
                  expiresIn = Some(3600),
                  keywords = List(
                    FilterKeywordAttribute(keyword = Some("foo"), wholeWord = Some(false)),
                    FilterKeywordAttribute(keyword = Some("bar"))
                  )
                )
      requests <- ref.get
    yield
      assertEquals(result.id.value, "20060")
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v2/filters")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(
        request.form,
        Some(
          List(
            "title" -> "Test filter",
            "context[]" -> "home",
            "context[]" -> "public",
            "filter_action" -> "warn",
            "expires_in" -> "3600",
            "keywords_attributes[][keyword]" -> "foo",
            "keywords_attributes[][whole_word]" -> "false",
            "keywords_attributes[][keyword]" -> "bar"
          )
        )
      )
  }

  test("create omits filter_action, expires_in and keywords when absent") {
    for
      pair      <- recording(Response(HttpStatus.Ok, filterJson))
      (ref, api) = pair
      _         <- api.create("Test filter", List(FilterContext.Home))
      requests  <- ref.get
    yield
      assertEquals(
        requests.head.form,
        Some(List("title" -> "Test filter", "context[]" -> "home"))
      )
  }

  test("create surfaces a 422 validation failure with the server message") {
    for
      pair <- recording(
                Response(
                  HttpStatus(422),
                  """{"error":"Validation failed: Context can't be blank"}"""
                )
              )
      (_, api) = pair
      result   <- api.create("Test filter", Nil).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.Http(
          HttpStatus(422),
          Some("Validation failed: Context can't be blank"),
          """{"error":"Validation failed: Context can't be blank"}"""
        )
      )
  }

  test("update PUTs a form that destroys and modifies keywords by id") {
    for
      pair      <- recording(Response(HttpStatus.Ok, filterJson))
      (ref, api) = pair
      _ <- api.update(
             Id("25933"),
             title = Some("test"),
             context = Some(List(FilterContext.Home, FilterContext.Notifications)),
             filterAction = Some(FilterAction.Blur),
             keywords = List(
               FilterKeywordAttribute(id = Some(Id("34978")), destroy = true),
               FilterKeywordAttribute(id = Some(Id("34979")), keyword = Some("baz"))
             )
           )
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.PUT)
      assertEquals(request.url, "https://code.party/api/v2/filters/25933")
      assertEquals(
        request.form,
        Some(
          List(
            "title" -> "test",
            "context[]" -> "home",
            "context[]" -> "notifications",
            "filter_action" -> "blur",
            "keywords_attributes[][id]" -> "34978",
            "keywords_attributes[][_destroy]" -> "true",
            "keywords_attributes[][id]" -> "34979",
            "keywords_attributes[][keyword]" -> "baz"
          )
        )
      )
  }

  test("update with every option absent sends an empty form") {
    for
      pair      <- recording(Response(HttpStatus.Ok, filterJson))
      (ref, api) = pair
      _         <- api.update(Id("25933"))
      requests  <- ref.get
    yield assertEquals(requests.head.form, Some(Nil))
  }

  test("delete targets the filter path") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.delete(Id("25933")).attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      assertEquals(requests.head.method, HttpMethod.DELETE)
      assertEquals(requests.head.url, "https://code.party/api/v2/filters/25933")
  }

  test("a 429 becomes RateLimited with Retry-After") {
    for
      pair <- recording(
                Response(
                  HttpStatus(429),
                  """{"error":"Too many requests"}""",
                  Map("Retry-After" -> "5")
                )
              )
      (_, api) = pair
      result   <- api.delete(Id("25933")).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(5.seconds), Some("Too many requests"))
      )
  }

  test("keywords lists the keywords attached to a filter") {
    for
      pair      <- recording(Response(HttpStatus.Ok, s"[$keywordJson]"))
      (ref, api) = pair
      result    <- api.keywords(Id("25933"))
      requests  <- ref.get
    yield
      assertEquals(result.map(_.keyword), List("baz"))
      assertEquals(requests.head.method, HttpMethod.GET)
      assertEquals(requests.head.url, "https://code.party/api/v2/filters/25933/keywords")
  }

  test("getKeyword targets the standalone keyword path") {
    for
      pair      <- recording(Response(HttpStatus.Ok, keywordJson))
      (ref, api) = pair
      result    <- api.getKeyword(Id("34979"))
      requests  <- ref.get
    yield
      assertEquals(result, FilterKeyword(Id("34979"), "baz", true))
      assertEquals(requests.head.url, "https://code.party/api/v2/filters/keywords/34979")
  }

  test("addKeyword POSTs keyword and whole_word, omitting whole_word when absent") {
    for
      withWord   <- recording(Response(HttpStatus.Ok, keywordJson))
      (refA, apiA) = withWord
      _          <- apiA.addKeyword(Id("25933"), "baz", wholeWord = Some(true))
      first      <- refA.get
      without    <- recording(Response(HttpStatus.Ok, keywordJson))
      (refB, apiB) = without
      _          <- apiB.addKeyword(Id("25933"), "baz")
      second     <- refB.get
    yield
      assertEquals(first.head.method, HttpMethod.POST)
      assertEquals(first.head.url, "https://code.party/api/v2/filters/25933/keywords")
      assertEquals(
        first.head.form,
        Some(List("keyword" -> "baz", "whole_word" -> "true"))
      )
      assertEquals(second.head.form, Some(List("keyword" -> "baz")))
  }

  test("updateKeyword PUTs the keyword path with an optional whole_word") {
    for
      pair      <- recording(Response(HttpStatus.Ok, keywordJson))
      (ref, api) = pair
      _         <- api.updateKeyword(Id("35583"), "other", wholeWord = Some(false))
      requests  <- ref.get
    yield
      assertEquals(requests.head.method, HttpMethod.PUT)
      assertEquals(requests.head.url, "https://code.party/api/v2/filters/keywords/35583")
      assertEquals(
        requests.head.form,
        Some(List("keyword" -> "other", "whole_word" -> "false"))
      )
  }

  test("deleteKeyword targets the keyword path") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.deleteKeyword(Id("35583")).attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      assertEquals(requests.head.method, HttpMethod.DELETE)
      assertEquals(requests.head.url, "https://code.party/api/v2/filters/keywords/35583")
  }

  test("statuses lists and getStatus fetches one status filter") {
    for
      listPair <- recording(Response(HttpStatus.Ok, s"[$statusJson]"))
      (listRef, listApi) = listPair
      listed   <- listApi.statuses(Id("25933"))
      listReq  <- listRef.get
      onePair  <- recording(Response(HttpStatus.Ok, statusJson))
      (oneRef, oneApi) = onePair
      one      <- oneApi.getStatus(Id("897"))
      oneReq   <- oneRef.get
    yield
      assertEquals(listed.map(_.statusId.value), List("109416512469928632"))
      assertEquals(listReq.head.url, "https://code.party/api/v2/filters/25933/statuses")
      assertEquals(one.statusId.value, "109416512469928632")
      assertEquals(oneReq.head.url, "https://code.party/api/v2/filters/statuses/897")
  }

  test("addStatus POSTs the status id to the filter's statuses path") {
    for
      pair      <- recording(Response(HttpStatus.Ok, statusJson))
      (ref, api) = pair
      result    <- api.addStatus(Id("25933"), Id("109416512469928632"))
      requests  <- ref.get
    yield
      assertEquals(result.id.value, "897")
      assertEquals(requests.head.method, HttpMethod.POST)
      assertEquals(requests.head.url, "https://code.party/api/v2/filters/25933/statuses")
      assertEquals(requests.head.form, Some(List("status_id" -> "109416512469928632")))
  }

  test("deleteStatus targets the status filter path") {
    for
      pair      <- recording(Response(HttpStatus.Ok, "{}"))
      (ref, api) = pair
      result    <- api.deleteStatus(Id("897")).attempt
      requests  <- ref.get
    yield
      assertEquals(result, Right(()))
      assertEquals(requests.head.method, HttpMethod.DELETE)
      assertEquals(requests.head.url, "https://code.party/api/v2/filters/statuses/897")
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair    <- recording(Response(HttpStatus.Ok, """{"id":"20060"}"""))
      (_, api) = pair
      result  <- api.get(Id("20060")).attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("an anonymous client fails authenticated filter calls without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, s"[$filterJson]"))
                )
      api    = Filters.live[IO](EndpointRunner.anonymous(backend, host))
      result <- api.list().attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v2/filters"))
      assertEquals(sent, 0)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair      <- recording(Response(HttpStatus.Ok, filterJson))
      (_, api)   = pair
      translated = api.mapK(identity)
      result    <- translated.get(Id("20060")).attempt
    yield assertEquals(result.map(_.filterAction), Right(FilterAction.Hide))
  }
