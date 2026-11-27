package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id, ReportCategory}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class ReportsSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val accountJson: String =
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
      |  "bot": false,
      |  "created_at": "2022-05-26T00:00:00.000Z"
      |}""".stripMargin

  private val reportJson: String =
    s"""{
       |  "id": "48914",
       |  "action_taken": false,
       |  "action_taken_at": null,
       |  "category": "spam",
       |  "comment": "Spam account",
       |  "forwarded": true,
       |  "created_at": "2022-08-25T09:56:16.763Z",
       |  "status_ids": ["108882889550545820"],
       |  "collection_ids": [],
       |  "rule_ids": null,
       |  "target_account": $accountJson
       |}""".stripMargin

  private def recording(response: Response): IO[(Ref[IO, List[Request]], Reports[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Reports.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("create posts the documented form fields, repeating status_ids[] and rule_ids[]") {
    for
      pair      <- recording(Response(HttpStatus.Ok, reportJson))
      (ref, api) = pair
      result <- api.create(
                  Id("108366849347798387"),
                  statusIds = List(Id("108882889550545820"), Id("108882889550545821")),
                  comment = Some("Spam account"),
                  category = Some(ReportCategory.Spam),
                  forward = Some(true),
                  ruleIds = List(Id("3"), Id("7"))
                )
      requests <- ref.get
    yield
      assertEquals(result.id.value, "48914")
      assertEquals(result.category, ReportCategory.Spam)
      assertEquals(result.statusIds, List(Id("108882889550545820")))
      assertEquals(result.ruleIds, Nil)
      assertEquals(result.comment, Some("Spam account"))
      assertEquals(result.targetAccount.username, "Baluke")
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/reports")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(request.body, None)
      assertEquals(
        request.form,
        Some(
          List(
            "account_id"   -> "108366849347798387",
            "status_ids[]" -> "108882889550545820",
            "status_ids[]" -> "108882889550545821",
            "comment"      -> "Spam account",
            "category"     -> "spam",
            "forward"      -> "true",
            "rule_ids[]"   -> "3",
            "rule_ids[]"   -> "7"
          )
        )
      )
  }

  test("create omits every option that was not supplied") {
    for
      pair      <- recording(Response(HttpStatus.Ok, reportJson))
      (ref, api) = pair
      _        <- api.create(Id("108366849347798387"))
      requests <- ref.get
    yield
      assertEquals(
        requests.head.form,
        Some(List("account_id" -> "108366849347798387"))
      )
  }

  test("create sends an explicit forward=false and the legal category when asked to") {
    for
      pair      <- recording(Response(HttpStatus.Ok, reportJson))
      (ref, api) = pair
      _ <- api.create(
             Id("1"),
             category = Some(ReportCategory.Legal),
             forward = Some(false)
           )
      requests <- ref.get
    yield
      assertEquals(
        requests.head.form,
        Some(
          List(
            "account_id" -> "1",
            "category"   -> "legal",
            "forward"    -> "false"
          )
        )
      )
  }

  test("a 404 raises a structured, non-retryable Http error") {
    for
      pair      <- recording(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, api)   = pair
      result    <- api.create(Id("1")).attempt
    yield
      val error = apiError(result)
      assert(error.isNotFound, s"expected isNotFound, got $error")
      assertEquals(error.retryable, false)
  }

  test("a 422 keeps the validation message") {
    for
      pair <- recording(
                Response(HttpStatus(422), """{"error":"Validation failed: Rule ids must be blank"}""")
              )
      (_, api) = pair
      result <- api.create(Id("1"), category = Some(ReportCategory.Spam), ruleIds = List(Id("3"))).attempt
    yield
      val error = apiError(result)
      assertEquals(
        error,
        ApiError.Http(
          HttpStatus(422),
          Some("Validation failed: Rule ids must be blank"),
          """{"error":"Validation failed: Rule ids must be blank"}"""
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
      result   <- api.create(Id("1")).attempt
    yield
      assertEquals(
        apiError(result),
        ApiError.RateLimited(Some(5.seconds), Some("Too many requests"))
      )
  }

  test("a malformed success body becomes a Decode error") {
    for
      pair      <- recording(Response(HttpStatus.Ok, """{"id":"48914"}"""))
      (_, api)   = pair
      result    <- api.create(Id("1")).attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], s"got ${apiError(result)}")
  }

  test("an anonymous client fails authenticated report calls without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      backend = HttpBackend.of[IO](_ =>
                  calls.update(_ + 1).as(Response(HttpStatus.Ok, reportJson))
                )
      api    <- IO.pure(Reports.live[IO](EndpointRunner.anonymous(backend, host)))
      result <- api.create(Id("1")).attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/reports"))
      assertEquals(sent, 0)
  }

  test("mapK identity keeps the algebra's behaviour") {
    val identity = new ~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair      <- recording(Response(HttpStatus.Ok, reportJson))
      (_, api)   = pair
      translated = api.mapK(identity)
      result    <- translated.create(Id("1")).attempt
    yield assertEquals(result.map(_.category), Right(ReportCategory.Spam))
  }
