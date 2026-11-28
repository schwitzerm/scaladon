package ca.schwitzer.scaladon.api

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration.*

import ca.schwitzer.scaladon.domain.{
  ApiError,
  ApiException,
  HttpStatus,
  OAuthGrantType,
  UserInfo
}
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, HttpMethod, Request, Response}

class OAuthSuite extends CatsEffectSuite:

  private val host = Host("code.party")
  private val token = BearerToken("secret")

  private val metadataJson: String =
    """{
      |  "issuer": "https://code.party/",
      |  "service_documentation": "https://docs.joinmastodon.org/",
      |  "authorization_endpoint": "https://code.party/oauth/authorize",
      |  "token_endpoint": "https://code.party/oauth/token",
      |  "app_registration_endpoint": "https://code.party/api/v1/apps",
      |  "revocation_endpoint": "https://code.party/oauth/revoke",
      |  "userinfo_endpoint": "https://code.party/oauth/userinfo",
      |  "scopes_supported": ["read", "write", "follow", "push", "profile"],
      |  "response_types_supported": ["code"],
      |  "response_modes_supported": ["query", "fragment", "form_post"],
      |  "code_challenge_methods_supported": ["S256"],
      |  "grant_types_supported": ["authorization_code", "client_credentials"],
      |  "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post"],
      |  "prompt_values_supported": ["create", "login"]
      |}""".stripMargin

  private val tokenJson: String =
    """{
      |  "access_token": "ZA-Yj3aBD8U8Cm7lKUp-lm9O9BmDgdhHzDeqsY8tlL0",
      |  "token_type": "Bearer",
      |  "scope": "read write follow",
      |  "created_at": 1573979017,
      |  "expires_in": 7200
      |}""".stripMargin

  private val userinfoJson: String =
    """{
      |  "iss": "https://code.party/",
      |  "sub": "https://code.party/users/john_mastodon",
      |  "name": "John Mastodon",
      |  "preferred_username": "john_mastodon",
      |  "profile": "https://code.party/@john_mastodon",
      |  "picture": "https://cdn.code.party/avatars/original/john.png"
      |}""".stripMargin

  private def recordingBackend(response: Response): IO[(Ref[IO, List[Request]], HttpBackend[IO])] =
    Ref.of[IO, List[Request]](Nil).map { ref =>
      ref -> HttpBackend.of[IO](request => ref.update(_ :+ request).as(response))
    }

  private def oauth(response: Response): IO[(Ref[IO, List[Request]], OAuth[IO])] =
    recordingBackend(response).map { case (ref, http) =>
      ref -> OAuth.live(EndpointRunner(http, host, Some(token)))
    }

  private def apiError[A](result: Either[Throwable, A]): ApiError =
    result match
      case Left(ApiException(error)) => error
      case other                     => fail(s"expected ApiException, got $other")

  private def queryOf(url: String): Map[String, String] =
    url
      .split('?')
      .lift(1)
      .toList
      .flatMap(_.split('&'))
      .map { pair =>
        pair.split("=", 2) match
          case Array(key, value) => decode(key) -> decode(value)
          case Array(key)        => decode(key) -> ""
          case _                 => fail(s"malformed query pair '$pair'")
      }
      .toMap

  private def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  test("metadata discovers the authorization server without a token") {
    for
      pair     <- oauth(Response(HttpStatus.Ok, metadataJson))
      (ref, client) = pair
      metadata <- client.metadata
      requests <- ref.get
    yield
      assertEquals(metadata.issuer, "https://code.party/")
      assertEquals(metadata.authorizationEndpoint, "https://code.party/oauth/authorize")
      assertEquals(metadata.tokenEndpoint, "https://code.party/oauth/token")
      assertEquals(metadata.appRegistrationEndpoint, Some("https://code.party/api/v1/apps"))
      assertEquals(metadata.revocationEndpoint, Some("https://code.party/oauth/revoke"))
      assertEquals(metadata.userinfoEndpoint, Some("https://code.party/oauth/userinfo"))
      assertEquals(metadata.scopesSupported, List("read", "write", "follow", "push", "profile"))
      assertEquals(metadata.responseTypesSupported, List("code"))
      assertEquals(metadata.grantTypesSupported, List("authorization_code", "client_credentials"))
      assertEquals(metadata.codeChallengeMethodsSupported, List("S256"))
      assertEquals(metadata.promptValuesSupported, List("create", "login"))
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/.well-known/oauth-authorization-server")
      assert(!request.headers.contains("Authorization"), "discovery is public")
  }

  test("authorization_code exchange posts a form with the PKCE verifier and no bearer token") {
    for
      pair     <- oauth(Response(HttpStatus.Ok, tokenJson))
      (ref, client) = pair
      token    <- client.token(
                    OAuthGrantType.AuthorizationCode,
                    clientId = "cid",
                    clientSecret = "csecret",
                    code = Some("authcode"),
                    redirectUri = Some("https://app.example/callback"),
                    scope = Some(List("read", "write", "follow")),
                    codeVerifier = Some("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
                  )
      requests <- ref.get
    yield
      assertEquals(token.accessToken, "ZA-Yj3aBD8U8Cm7lKUp-lm9O9BmDgdhHzDeqsY8tlL0")
      assertEquals(token.tokenType, "Bearer")
      assertEquals(token.scope, List("read", "write", "follow"))
      assertEquals(token.createdAt, Instant.ofEpochSecond(1573979017L))
      assertEquals(token.expiresIn, Some(7200L))
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/oauth/token")
      assertEquals(
        request.form,
        Some(
          List(
            "grant_type"    -> "authorization_code",
            "code"          -> "authcode",
            "redirect_uri"  -> "https://app.example/callback",
            "client_id"     -> "cid",
            "client_secret" -> "csecret",
            "scope"         -> "read write follow",
            "code_verifier" -> "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
          )
        )
      )
      assert(!request.headers.contains("Authorization"), "token exchange authenticates by form")
  }

  test("client_credentials exchange omits code, redirect_uri and code_verifier") {
    for
      pair     <- oauth(Response(HttpStatus.Ok, """{
                    |  "access_token": "app-token",
                    |  "token_type": "Bearer",
                    |  "scope": "read",
                    |  "created_at": 1573979017
                    |}""".stripMargin))
      (ref, client) = pair
      token    <- client.token(
                    OAuthGrantType.ClientCredentials,
                    clientId = "cid",
                    clientSecret = "csecret",
                    scope = Some(List("read"))
                  )
      requests <- ref.get
    yield
      assertEquals(token.accessToken, "app-token")
      assertEquals(token.scope, List("read"))
      assertEquals(token.expiresIn, None)
      assertEquals(
        requests.head.form,
        Some(
          List(
            "grant_type"    -> "client_credentials",
            "client_id"     -> "cid",
            "client_secret" -> "csecret",
            "scope"         -> "read"
          )
        )
      )
  }

  test("revoke posts client credentials and the token without a bearer header") {
    for
      pair     <- oauth(Response(HttpStatus.Ok, "{}"))
      (ref, client) = pair
      _        <- client.revoke("revoked-token", "cid", "csecret")
      requests <- ref.get
    yield
      val request = requests.head
      assertEquals(request.method, HttpMethod.POST)
      assertEquals(request.url, "https://code.party/oauth/revoke")
      assertEquals(
        request.form,
        Some(
          List(
            "client_id"     -> "cid",
            "client_secret" -> "csecret",
            "token"         -> "revoked-token"
          )
        )
      )
      assert(!request.headers.contains("Authorization"), "revoke is a public endpoint")
  }

  test("userinfo sends the bearer token and decodes the OIDC claims") {
    for
      pair     <- oauth(Response(HttpStatus.Ok, userinfoJson))
      (ref, client) = pair
      info     <- client.userinfo
      requests <- ref.get
    yield
      assertEquals(
        info,
        UserInfo(
          issuer = "https://code.party/",
          subject = "https://code.party/users/john_mastodon",
          name = "John Mastodon",
          preferredUsername = "john_mastodon",
          profile = "https://code.party/@john_mastodon",
          picture = "https://cdn.code.party/avatars/original/john.png"
        )
      )
      val request = requests.head
      assertEquals(request.method, HttpMethod.GET)
      assertEquals(request.url, "https://code.party/oauth/userinfo")
      assertEquals(request.headers.get("Authorization"), Some("Bearer secret"))
  }

  test("userinfo on an anonymous client fails before touching the network") {
    for
      calls <- Ref.of[IO, Int](0)
      http = HttpBackend.of[IO](_ => calls.update(_ + 1).as(Response(HttpStatus.Ok, userinfoJson)))
      client = OAuth.live(EndpointRunner.anonymous(http, host))
      result <- client.userinfo.attempt
      sent   <- calls.get
    yield
      assertEquals(apiError(result), ApiError.NotAuthenticated("/oauth/userinfo"))
      assertEquals(sent, 0)
  }

  test("a 429 on token exchange becomes RateLimited with Retry-After") {
    val response = Response(
      HttpStatus(429),
      """{"error":"Too many requests"}""",
      Map("Retry-After" -> "5")
    )
    for
      pair   <- oauth(response)
      client = pair._2
      result <- client
                  .token(OAuthGrantType.ClientCredentials, "cid", "csecret")
                  .attempt
    yield
      val error = apiError(result)
      assertEquals(error, ApiError.RateLimited(Some(5.seconds), Some("Too many requests")))
      assertEquals(error.retryable, true)
  }

  test("invalid client credentials are an identifiable 401") {
    for
      pair   <- oauth(Response(HttpStatus.Unauthorized, """{"error":"invalid_client"}"""))
      client = pair._2
      result <- client
                  .token(OAuthGrantType.ClientCredentials, "cid", "wrong")
                  .attempt
    yield
      val error = apiError(result)
      assert(error.isUnauthorized, s"expected isUnauthorized, got $error")
      assertEquals(error.retryable, false)
  }

  test("a malformed token body is a non-retryable Decode error") {
    for
      pair   <- oauth(Response(HttpStatus.Ok, """{"access_token":"only-this"}"""))
      client = pair._2
      result <- client
                  .token(OAuthGrantType.ClientCredentials, "cid", "csecret")
                  .attempt
    yield
      val error = apiError(result)
      assert(error.isInstanceOf[ApiError.Decode], s"got $error")
      assertEquals(error.retryable, false)
  }

  test("mapK preserves the algebra under the identity transformation") {
    val identity = new cats.~>[IO, IO]:
      def apply[A](fa: IO[A]): IO[A] = fa
    for
      pair     <- oauth(Response(HttpStatus.Ok, userinfoJson))
      (ref, client) = pair
      translated = client.mapK(identity)
      info     <- translated.userinfo
      requests <- ref.get
    yield
      assertEquals(info.preferredUsername, "john_mastodon")
      assertEquals(requests.length, 1)
  }

  test("authorizeUrl carries the code flow, scopes, state, PKCE and prompt=create") {
    val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    val url = OAuth.authorizeUrl(
      host,
      clientId = "cid",
      redirectUri = "https://app.example/callback",
      scopes = List("read", "write", "profile"),
      state = Some("xyz"),
      codeChallenge = Some(OAuth.codeChallengeS256(verifier)),
      promptCreate = true
    )
    val query = queryOf(url)
    assert(url.startsWith("https://code.party/oauth/authorize?"), url)
    assertEquals(query("response_type"), "code")
    assertEquals(query("client_id"), "cid")
    assertEquals(query("redirect_uri"), "https://app.example/callback")
    assertEquals(query("scope"), "read write profile")
    assertEquals(query("state"), "xyz")
    assertEquals(query("code_challenge"), OAuth.codeChallengeS256(verifier))
    assertEquals(query("code_challenge_method"), "S256")
    assertEquals(query("prompt"), "create")
  }

  test("authorizeUrl omits absent options instead of sending empty parameters") {
    val query = queryOf(
      OAuth.authorizeUrl(
        host,
        clientId = "cid",
        redirectUri = "urn:ietf:wg:oauth:2.0:oob",
        scopes = Nil
      )
    )
    assertEquals(query.keySet, Set("response_type", "client_id", "redirect_uri"))
    assertEquals(query("redirect_uri"), "urn:ietf:wg:oauth:2.0:oob")
  }

  test("codeChallengeS256 matches the RFC 7636 test vector") {
    assertEquals(
      OAuth.codeChallengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    )
  }
