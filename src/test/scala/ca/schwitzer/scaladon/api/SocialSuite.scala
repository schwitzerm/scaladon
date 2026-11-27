package ca.schwitzer.scaladon.api

import cats.~>
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, Id}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class SocialSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val accountJson: String =
    """{
      |  "id": "585315",
      |  "username": "admin",
      |  "acct": "admin@happylittle.cloudns.cc",
      |  "display_name": "Happy Little Clouds",
      |  "locked": false,
      |  "created_at": "2016-11-30T00:00:00.000Z",
      |  "note": "<p>admin</p>",
      |  "url": "https://code.party/@admin",
      |  "avatar": "https://code.party/a.png",
      |  "header": "https://code.party/h.png",
      |  "followers_count": 12,
      |  "following_count": 4,
      |  "statuses_count": 99
      |}""".stripMargin

  private val accountList: String = s"[$accountJson]"

  private val relationshipJson: String =
    """{
      |  "id": "8889777",
      |  "following": false,
      |  "showing_reblogs": false,
      |  "notifying": false,
      |  "followed_by": true,
      |  "blocking": false,
      |  "blocked_by": false,
      |  "muting": false,
      |  "muting_notifications": false,
      |  "requested": false,
      |  "domain_blocking": false,
      |  "endorsed": false
      |}""".stripMargin

  private def recording(
      response: Response
  ): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  private val identity = new ~>[IO, IO]:
    def apply[A](fa: IO[A]): IO[A] = fa

  test("blocks list pages with max_id, since_id, min_id and limit and decodes accounts") {
    for
      pair   <- recording(Response(HttpStatus.Ok, accountList))
      (ref, http) = pair
      api = Blocks.live[IO](EndpointRunner(http, host, Some(token)))
      blocked <- api.list(
                   maxId = Some(Id("100")),
                   sinceId = Some(Id("50")),
                   minId = Some(Id("10")),
                   limit = Some(40)
                 )
      requests <- ref.get
    yield
      assertEquals(
        requests.head.url,
        "https://code.party/api/v1/blocks?max_id=100&since_id=50&min_id=10&limit=40"
      )
      assertEquals(requests.head.method, HttpMethod.GET)
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(blocked.map(_.id.value), List("585315"))
      assertEquals(blocked.head.acct, "admin@happylittle.cloudns.cc")
  }

  test("blocks list omits pagination when no parameters are given") {
    for
      pair   <- recording(Response(HttpStatus.Ok, "[]"))
      (ref, http) = pair
      api = Blocks.live[IO](EndpointRunner(http, host, Some(token)))
      _      <- api.list()
      requests <- ref.get
    yield assertEquals(requests.head.url, "https://code.party/api/v1/blocks")
  }

  test("mutes list pages with max_id, since_id and limit and decodes accounts") {
    for
      pair   <- recording(Response(HttpStatus.Ok, accountList))
      (ref, http) = pair
      api = Mutes.live[IO](EndpointRunner(http, host, Some(token)))
      muted  <- api.list(maxId = Some(Id("9")), sinceId = Some(Id("7")), limit = Some(2))
      requests <- ref.get
    yield
      assertEquals(
        requests.head.url,
        "https://code.party/api/v1/mutes?max_id=9&since_id=7&limit=2"
      )
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(muted.map(_.id.value), List("585315"))
  }

  test("follows sends uri as a form field to POST /api/v1/follows and decodes the relationship") {
    for
      pair   <- recording(Response(HttpStatus.Ok, relationshipJson))
      (ref, http) = pair
      api = Follows.live[IO](EndpointRunner(http, host, Some(token)))
      relationship <- api.follow("acct:user@remote.example")
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/api/v1/follows")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(request.form, Some(List("uri" -> "acct:user@remote.example")))
      assertEquals(relationship.id.value, "8889777")
      assertEquals(relationship.followedBy, true)
  }

  test("follow requests list pages and decodes pending accounts") {
    for
      pair   <- recording(Response(HttpStatus.Ok, accountList))
      (ref, http) = pair
      api = FollowRequests.live[IO](EndpointRunner(http, host, Some(token)))
      pending <- api.list(maxId = Some(Id("7163058")), limit = Some(2))
      requests <- ref.get
    yield
      assertEquals(
        requests.head.url,
        "https://code.party/api/v1/follow_requests?max_id=7163058&limit=2"
      )
      assertEquals(requests.head.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(pending.map(_.id.value), List("585315"))
  }

  test("authorize and reject are empty form posts that decode the updated relationship") {
    for
      pair   <- recording(Response(HttpStatus.Ok, relationshipJson))
      (ref, http) = pair
      api = FollowRequests.live[IO](EndpointRunner(http, host, Some(token)))
      accepted <- api.authorize(Id("8889777"))
      rejected <- api.reject(Id("8889777"))
      requests <- ref.get
    yield
      assertEquals(
        requests.map(_.url),
        List(
          "https://code.party/api/v1/follow_requests/8889777/authorize",
          "https://code.party/api/v1/follow_requests/8889777/reject"
        )
      )
      assert(requests.forall(_.method == HttpMethod.POST))
      assert(requests.forall(_.form == Some(Nil)))
      assertEquals(accepted.followedBy, true)
      assertEquals(rejected.following, false)
  }

  test("a follow request for an unknown account raises NotFound") {
    for
      pair   <- recording(Response(HttpStatus.NotFound, """{"error":"Record not found"}"""))
      (_, http) = pair
      api = FollowRequests.live[IO](EndpointRunner(http, host, Some(token)))
      result <- api.authorize(Id("42")).attempt
    yield assertEquals(apiError(result).isNotFound, true)
  }

  test("a 429 from blocks becomes RateLimited with Retry-After") {
    val response = Response(
      HttpStatus(429),
      """{"error":"Too many requests"}""",
      Map("Retry-After" -> "5")
    )
    for
      pair   <- recording(response)
      (_, http) = pair
      api = Blocks.live[IO](EndpointRunner(http, host, Some(token)))
      result <- api.list().attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.RateLimited(Some(5.seconds), Some("Too many requests")))
      assertEquals(error.retryable, true)
  }

  test("a malformed mute list body becomes a Decode error") {
    for
      pair   <- recording(Response(HttpStatus.Ok, """{"id":"1"}"""))
      (_, http) = pair
      api = Mutes.live[IO](EndpointRunner(http, host, Some(token)))
      result <- api.list().attempt
    yield assert(apiError(result).isInstanceOf[ApiError.Decode], apiError(result).toString)
  }

  test("an anonymous follow fails without touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      http = HttpBackend.of[IO](_ => calls.update(_ + 1).as(Response(HttpStatus.Ok, relationshipJson)))
      api = Follows.live[IO](EndpointRunner.anonymous(http, host))
      result <- api.follow("acct:user@remote.example").attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/api/v1/follows"))
      assertEquals(sent, 0)
  }

  test("mapK translates each social algebra between effects") {
    for
      pair   <- recording(Response(HttpStatus.Ok, accountList))
      (_, http) = pair
      runner = EndpointRunner(http, host, Some(token))
      blocks <- Blocks.live[IO](runner).mapK(identity).list().attempt
      mutes  <- Mutes.live[IO](runner).mapK(identity).list().attempt
    yield
      assertEquals(blocks.map(_.map(_.id.value)), Right(List("585315")))
      assertEquals(mutes.map(_.map(_.id.value)), Right(List("585315")))
  }

  test("mapK translates follows and follow requests between effects") {
    for
      followPair <- recording(Response(HttpStatus.Ok, relationshipJson))
      (_, followHttp) = followPair
      listPair <- recording(Response(HttpStatus.Ok, accountList))
      (_, listHttp) = listPair
      followed <- Follows.live[IO](EndpointRunner(followHttp, host, Some(token)))
                    .mapK(identity)
                    .follow("acct:user@remote.example")
                    .attempt
      requests <- FollowRequests.live[IO](EndpointRunner(listHttp, host, Some(token)))
                    .mapK(identity)
                    .list()
                    .attempt
    yield
      assertEquals(followed.map(_.id.value), Right("8889777"))
      assertEquals(requests.map(_.map(_.id.value)), Right(List("585315")))
  }
