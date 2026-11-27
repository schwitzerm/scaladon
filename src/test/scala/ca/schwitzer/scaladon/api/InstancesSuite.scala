package ca.schwitzer.scaladon.api

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus, TimelineAccess}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class InstancesSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val instanceJson: String =
    """{
      |  "domain": "code.party",
      |  "title": "Code Party",
      |  "version": "5.0.0",
      |  "source_url": "https://github.com/mastodon/mastodon",
      |  "description": "A test server",
      |  "api_versions": { "mastodon": 12 },
      |  "registrations": {
      |    "enabled": true,
      |    "approval_required": false,
      |    "reason_required": false,
      |    "message": null,
      |    "min_age": 16,
      |    "url": null,
      |    "oauth": true
      |  },
      |  "configuration": {
      |    "urls": {
      |      "streaming": "wss://code.party",
      |      "status": null,
      |      "about": "https://code.party/about",
      |      "privacy_policy": null,
      |      "terms_of_service": null
      |    },
      |    "vapid": { "public_key": "BCk-test-key" },
      |    "accounts": {
      |      "max_display_name_length": 30,
      |      "max_note_length": 500,
      |      "max_avatar_description_length": 1500,
      |      "max_header_description_length": 1500,
      |      "max_featured_tags": 10,
      |      "max_pinned_statuses": 5,
      |      "max_profile_fields": 4,
      |      "profile_field_name_limit": 255,
      |      "profile_field_value_limit": 255
      |    },
      |    "statuses": {
      |      "max_characters": 500,
      |      "max_media_attachments": 4,
      |      "characters_reserved_per_url": 23
      |    },
      |    "media_attachments": {
      |      "description_limit": 1500,
      |      "supported_mime_types": ["image/png", "video/mp4"],
      |      "image_size_limit": 16777216,
      |      "image_matrix_limit": 33177600,
      |      "video_size_limit": 103809024,
      |      "video_matrix_limit": 8294400,
      |      "video_frame_rate_limit": 120
      |    },
      |    "polls": {
      |      "max_options": 4,
      |      "max_characters_per_option": 50,
      |      "min_expiration": 300,
      |      "max_expiration": 2629746
      |    },
      |    "translation": { "enabled": true },
      |    "timelines_access": {
      |      "live_feeds": { "local": "public", "remote": "authenticated" },
      |      "hashtag_feeds": { "local": "public", "remote": "disabled" },
      |      "trending_link_feeds": { "local": "disabled", "remote": "public" }
      |    },
      |    "limited_federation": false,
      |    "collections": {
      |      "max_items": 25,
      |      "max_name_length": 40,
      |      "max_description_length": 100
      |    }
      |  },
      |  "contact": { "email": "admin@code.party" }
      |}""".stripMargin

  private def recording(response: Response): IO[(Ref[IO, List[Request]], Instances[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      val backend = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      ref -> Instances.live[IO](EndpointRunner(backend, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("v2 decodes the API version gate and the server limits") {
    for
      pair     <- recording(Response(HttpStatus.Ok, instanceJson))
      (ref, instances) = pair
      instance <- instances.v2
      requests <- ref.get
    yield
      assertEquals(instance.apiVersions.mastodon, 12)
      assertEquals(instance.version, "5.0.0")
      assertEquals(instance.registrations.oauth, true)
      assertEquals(instance.registrations.minAge, Some(16))
      assertEquals(instance.configuration.statuses.maxCharacters, 500)
      assertEquals(instance.configuration.collections.maxItems, 25)
      assertEquals(instance.configuration.vapid.publicKey, "BCk-test-key")
      assertEquals(instance.configuration.timelinesAccess.liveFeeds.remote, TimelineAccess.Authenticated)
      assertEquals(instance.contactEmail, Some("admin@code.party"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v2/instance")
      assert(!request.headers.contains("Authorization"), "public endpoint must not carry a token")
  }

  test("an unknown timeline access value is a decode failure, never a default") {
    val bogus = instanceJson.replace("\"public\"", "\"bogus\"")
    for
      pair     <- recording(Response(HttpStatus.Ok, bogus))
      (_, instances) = pair
      result   <- instances.v2.attempt
    yield
      apiError(result) match
        case ApiError.Decode(_) => ()
        case other              => fail(s"expected Decode, got $other")
  }

  test("runWithResponse keeps raw headers for AsyncRefresh") {
    val response = Response(
      HttpStatus.Ok,
      instanceJson,
      Map("Mastodon-Async-Refresh" -> "refresh-1")
    )
    for
      ref <- Ref.of[IO, List[Request]](Nil)
      http = HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
      runner = EndpointRunner(http, host, Some(token))
      pair <- runner.runWithResponse(Instances.v2Endpoint)
    yield
      assertEquals(pair._1.apiVersions.mastodon, 12)
      assertEquals(pair._2.header("Mastodon-Async-Refresh"), Some("refresh-1"))
  }

  test("mapK translates the algebra between effects") {
    val identity = new cats.~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair     <- recording(Response(HttpStatus.Ok, instanceJson))
      (ref, instances) = pair
      instance <- instances.mapK(identity).v2
      requests <- ref.get
    yield
      assertEquals(instance.apiVersions.mastodon, 12)
      assertEquals(requests.map(_.url), List("https://code.party/api/v2/instance"))
  }
