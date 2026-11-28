package ca.schwitzer.scaladon.http

import io.circe.Json
import munit.FunSuite

class RequestSuite extends FunSuite:

  private val url = "https://code.party/api/v1/statuses"

  test("toString redacts an Authorization bearer token") {
    val token    = "super-secret-token"
    val rendered = Request(HttpMethod.GET, url, Map("Authorization" -> s"Bearer $token")).toString
    assert(rendered.contains("Authorization=**redacted**"), rendered)
    assert(!rendered.contains(token), rendered)
    assert(!rendered.contains("Bearer super-secret-token"), rendered)
  }

  test("header redaction is case-insensitive and covers Proxy-Authorization") {
    val rendered = Request(
      HttpMethod.GET,
      url,
      Map(
        "authorization"       -> "Bearer lower-secret",
        "AUTHORIZATION"       -> "Bearer upper-secret",
        "Proxy-Authorization" -> "Basic proxy-secret"
      )
    ).toString
    assertEquals(rendered.split("\\*\\*redacted\\*\\*").length - 1, 3, rendered)
    List("lower-secret", "upper-secret", "proxy-secret").foreach(secret =>
      assert(!rendered.contains(secret), rendered)
    )
  }

  test("credential-bearing form values are redacted while other fields stay visible") {
    val secrets = List(
      "client_secret" -> "client-secret-value",
      "code"          -> "authorization-code-value",
      "refresh_token" -> "refresh-token-value",
      "access_token"  -> "access-token-value",
      "password"      -> "password-value"
    )
    val rendered = Request(
      HttpMethod.POST,
      "https://code.party/oauth/token",
      form = Some(
        secrets ++ List(
          "grant_type"   -> "authorization_code",
          "redirect_uri" -> "https://client.example/callback"
        )
      )
    ).toString
    secrets.foreach { case (name, value) =>
      assert(rendered.contains(s"$name=**redacted**"), rendered)
      assert(!rendered.contains(value), rendered)
    }
    assert(rendered.contains("grant_type=authorization_code"), rendered)
    assert(rendered.contains("redirect_uri=https://client.example/callback"), rendered)
  }

  test("redacts the OAuth revoke token") {
    val rendered = Request(
      HttpMethod.POST,
      "https://code.party/oauth/revoke",
      form = Some(
        List(
          "client_id"     -> "client-id",
          "client_secret" -> "client-secret-value",
          "token"         -> "ACCESS-TOKEN-XYZ"
        )
      )
    ).toString
    assert(rendered.contains("token=**redacted**"), rendered)
    assert(!rendered.contains("ACCESS-TOKEN-XYZ"), rendered)
    assert(rendered.contains("client_id=client-id"), rendered)
  }

  test("redacts the PKCE code verifier") {
    val rendered = Request(
      HttpMethod.POST,
      "https://code.party/oauth/token",
      form = Some(
        List(
          "grant_type"    -> "authorization_code",
          "code"          -> "authorization-code-value",
          "client_id"     -> "client-id",
          "client_secret" -> "client-secret-value",
          "code_verifier" -> "PKCE-VERIFIER-XYZ",
          "redirect_uri"  -> "https://client.example/callback"
        )
      )
    ).toString
    assert(rendered.contains("code_verifier=**redacted**"), rendered)
    assert(!rendered.contains("PKCE-VERIFIER-XYZ"), rendered)
    assert(rendered.contains("redirect_uri=https://client.example/callback"), rendered)
  }

  test("redacts nested push auth keys case-insensitively") {
    val rendered = Request(
      HttpMethod.POST,
      "https://code.party/api/v1/push/subscription",
      form = Some(
        List(
          "subscription[endpoint]"     -> "https://push.example/xyz",
          "subscription[keys][p256dh]" -> "p256dh-key",
          "Subscription[Keys][Auth]"   -> "push-auth-secret"
        )
      )
    ).toString
    assert(rendered.contains("Subscription[Keys][Auth]=**redacted**"), rendered)
    assert(!rendered.contains("push-auth-secret"), rendered)
    assert(rendered.contains("subscription[keys][p256dh]=p256dh-key"), rendered)
  }

  test("a clean request renders its method, url, headers and payload presence") {
    val rendered = Request(
      HttpMethod.POST,
      url,
      Map("X-Trace-Id" -> "trace-123"),
      body = Some(Json.obj("status" -> Json.fromString("body-secret"))),
      multipart = Some(Multipart(List(MultipartPart.text("description", "multipart-secret"))))
    ).toString
    assert(rendered.contains("POST"), rendered)
    assert(rendered.contains(url), rendered)
    assert(rendered.contains("X-Trace-Id=trace-123"), rendered)
    assert(rendered.contains("body=present"), rendered)
    assert(rendered.contains("multipart=present"), rendered)
    assert(!rendered.contains("body-secret"), rendered)
    assert(!rendered.contains("multipart-secret"), rendered)
  }

  test("absent body, form and multipart render as absent markers") {
    val rendered = Request(HttpMethod.GET, url).toString
    assert(rendered.contains("body=absent"), rendered)
    assert(rendered.contains("form=absent"), rendered)
    assert(rendered.contains("multipart=absent"), rendered)
  }
