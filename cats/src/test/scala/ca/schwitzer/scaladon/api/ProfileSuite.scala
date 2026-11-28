package ca.schwitzer.scaladon.api

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import ca.schwitzer.scaladon.domain.{ApiError, ApiException, HttpStatus}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class ProfileSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val profileJson: String =
    """{
      |  "id": "116222600881276277",
      |  "display_name": "Documentation user",
      |  "note": "I'm only here as an example for documentation",
      |  "fields": [
      |    { "name": "pronouns", "value": "it/its", "verified_at": null }
      |  ],
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
      |  "show_media_replies": true,
      |  "show_featured": true,
      |  "attribution_domains": ["articles.example.com"],
      |  "formatted_note": "<p>I'm only here as an example for documentation</p>",
      |  "formatted_fields": [
      |    { "name": "pronouns", "value": "<a href=\"https://example.com\">it/its</a>", "verified_at": null }
      |  ]
      |}""".stripMargin

  private def recordingBackend(response: Response): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  private def profile(response: Response): IO[(Ref[IO, List[Request]], Profile[IO])] =
    recordingBackend(response).map { case (ref, http) =>
      ref -> Profile.live[IO](EndpointRunner(http, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  test("get targets /api/v1/profile and keeps raw and formatted values apart") {
    for
      pair    <- profile(Response(HttpStatus.Ok, profileJson))
      (ref, api) = pair
      result  <- api.get
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/api/v1/profile")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(result.note, "I'm only here as an example for documentation")
      assertEquals(result.formattedNote, Some("<p>I'm only here as an example for documentation</p>"))
      assertEquals(result.fields.map(_.value), List("it/its"))
      assertEquals(result.formattedFields.map(_.name), List("pronouns"))
      assertEquals(result.avatar, None)
      assertEquals(result.avatarDescription, "")
      assertEquals(result.showMedia, true)
      assertEquals(result.showMediaReplies, true)
      assertEquals(result.showFeatured, true)
      assertEquals(result.attributionDomains, List("articles.example.com"))
  }

  test("update sends a form with only the provided fields") {
    for
      pair   <- profile(Response(HttpStatus.Ok, profileJson))
      (ref, api) = pair
      _      <- api.update(
                  displayName = Some("New name"),
                  note = Some("new bio"),
                  locked = Some(true),
                  showMedia = Some(false),
                  attributionDomains = List("a.example", "b.example"),
                  fields = List("Website" -> "https://x.example", "Pronouns" -> "they/them")
                )
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.PATCH)
      assertEquals(request.url, "https://code.party/api/v1/profile")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
      assertEquals(request.body, None)
      assertEquals(
        request.form,
        Some(
          List(
            "display_name"                  -> "New name",
            "note"                          -> "new bio",
            "locked"                        -> "true",
            "show_media"                    -> "false",
            "attribution_domains[]"         -> "a.example",
            "attribution_domains[]"         -> "b.example",
            "fields_attributes[0][name]"    -> "Website",
            "fields_attributes[0][value]"   -> "https://x.example",
            "fields_attributes[1][name]"    -> "Pronouns",
            "fields_attributes[1][value]"   -> "they/them"
          )
        )
      )
  }

  test("update with an empty option clears that field rather than omitting it") {
    for
      pair     <- profile(Response(HttpStatus.Ok, profileJson))
      (ref, api) = pair
      _        <- api.update(note = Some(""))
      requests <- ref.get
    yield
      assertEquals(requests.head.form, Some(List("note" -> "")))
      assertEquals(requests.head.body, None)
  }

  test("a 422 keeps the validation message") {
    for
      pair   <- profile(Response(HttpStatus(422), """{"error":"Validation failed: Note is too long"}"""))
      result <- pair._2.update(note = Some("x")).attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.Http(HttpStatus(422), Some("Validation failed: Note is too long"), """{"error":"Validation failed: Note is too long"}"""))
  }

  test("a 401 is identifiable as an authorization failure") {
    for
      pair   <- profile(Response(HttpStatus.Unauthorized, """{"error":"The access token is invalid"}"""))
      result <- pair._2.get.attempt
    yield assertEquals(apiError(result).isUnauthorized, true)
  }

  test("mapK translates the profile algebra between effects") {
    val identity = new cats.~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair   <- profile(Response(HttpStatus.Ok, profileJson))
      result <- pair._2.mapK(identity).get.attempt
    yield assertEquals(result.map(_.note), Right("I'm only here as an example for documentation"))
  }
