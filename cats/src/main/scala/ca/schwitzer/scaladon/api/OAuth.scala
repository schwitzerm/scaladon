package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{OAuthGrantType, OAuthServerMetadata, OAuthToken, UserInfo}
import ca.schwitzer.scaladon.http.{Host, HttpMethod}

/** The OAuth surface of a Mastodon server, as an algebra of values.
  *
  * This follows the current flows only: authorization code (with optional PKCE) and client
  * credentials. The password grant was removed and is not modelled. Discovery comes from
  * [[metadata]] rather than from guessing by instance version, and [[userinfo]] returns the OIDC
  * claims the server exposes to tokens with the `profile` scope.
  *
  * `F` is the effect; failures are raised as `ApiException(ApiError)` like every other algebra.
  */
trait OAuth[F[_]]:

  /** `GET /.well-known/oauth-authorization-server` — public, no token. */
  def metadata: F[OAuthServerMetadata]

  /** `POST /oauth/token` — exchanges an authorization code (optionally with PKCE) or obtains an
    * app-only token with `client_credentials`. The client credentials travel in the form body.
    *
    * `code` and `redirectUri` are required for `authorization_code`; `scope` is meaningful for
    * `client_credentials`. Absent options are omitted from the form, never sent empty.
    */
  def token(
      grantType: OAuthGrantType,
      clientId: String,
      clientSecret: String,
      code: Option[String] = None,
      redirectUri: Option[String] = None,
      scope: Option[List[String]] = None,
      codeVerifier: Option[String] = None
  ): F[OAuthToken]

  /** `POST /oauth/revoke` — revokes a token the client owns. Public endpoint: the client
    * credentials and the token are form fields, and no bearer header is sent.
    */
  def revoke(token: String, clientId: String, clientSecret: String): F[Unit]

  /** `GET /oauth/userinfo` — OIDC claims for the authenticated user (`profile` scope). */
  def userinfo: F[UserInfo]

  def mapK[G[_]: MonadThrow](transform: F ~> G): OAuth[G]

object OAuth:

  def live[F[_]](runner: EndpointRunner[F]): OAuth[F] =
    new LiveOAuth[F](runner)

  def metadataEndpoint: Endpoint[OAuthServerMetadata] =
    Endpoint.get[OAuthServerMetadata](
      "/.well-known/oauth-authorization-server",
      authenticated = false
    )

  def tokenEndpoint(
      grantType: OAuthGrantType,
      clientId: String,
      clientSecret: String,
      code: Option[String],
      redirectUri: Option[String],
      scope: Option[List[String]],
      codeVerifier: Option[String]
  ): Endpoint[OAuthToken] =
    Endpoint.postForm[OAuthToken](
      "/oauth/token",
      tokenFields(grantType, clientId, clientSecret, code, redirectUri, scope, codeVerifier),
      authenticated = false
    )

  def revokeEndpoint(token: String, clientId: String, clientSecret: String): Endpoint[Unit] =
    Endpoint.unitForm(
      HttpMethod.POST,
      "/oauth/revoke",
      List(
        "client_id"     -> clientId,
        "client_secret" -> clientSecret,
        "token"         -> token
      ),
      authenticated = false
    )

  def userinfoEndpoint: Endpoint[UserInfo] =
    Endpoint.get[UserInfo]("/oauth/userinfo")

  /** Builds the browser URL for `GET /oauth/authorize`.
    *
    * `response_type` is always `code`; scopes are space-separated; `state` is passed through
    * unchanged; a `codeChallenge` also sets `code_challenge_method=S256` (the only method Mastodon
    * supports). `promptCreate` adds `prompt=create`, the Mastodon 5.0 sign-up flow that replaced
    * `POST /api/v1/accounts`. Empty options are omitted rather than sent as blank parameters.
    */
  def authorizeUrl(
      host: Host,
      clientId: String,
      redirectUri: String,
      scopes: List[String],
      state: Option[String] = None,
      codeChallenge: Option[String] = None,
      promptCreate: Boolean = false
  ): String =
    val required = List(
      "response_type" -> "code",
      "client_id"     -> clientId,
      "redirect_uri"  -> redirectUri
    )
    val optional =
      scopes.filter(_.nonEmpty) match
        case Nil     => Nil
        case granted => List("scope" -> granted.mkString(" "))
    val stateParam = optionalParam("state", state)
    val pkce = optionalParam("code_challenge", codeChallenge)
    val method = pkce.map(_ => "code_challenge_method" -> "S256")
    val prompt = if promptCreate then List("prompt" -> "create") else Nil
    val query = (required ++ optional ++ stateParam ++ pkce ++ method ++ prompt)
      .map { case (key, value) => s"${encode(key)}=${encode(value)}" }
      .mkString("&")
    s"${host.baseUrl}/oauth/authorize?$query"

  /** RFC 7636 S256 challenge: BASE64URL(SHA-256(verifier)) without padding. */
  def codeChallengeS256(verifier: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding.encodeToString(digest)

  private def tokenFields(
      grantType: OAuthGrantType,
      clientId: String,
      clientSecret: String,
      code: Option[String],
      redirectUri: Option[String],
      scope: Option[List[String]],
      codeVerifier: Option[String]
  ): List[(String, String)] =
    List("grant_type" -> grantType.wire) ++
      optionalParam("code", code) ++
      optionalParam("redirect_uri", redirectUri) ++
      List("client_id" -> clientId, "client_secret" -> clientSecret) ++
      scope.filter(_.nonEmpty).toList.map(granted => "scope" -> granted.mkString(" ")) ++
      optionalParam("code_verifier", codeVerifier)

  private def optionalParam(name: String, value: Option[String]): List[(String, String)] =
    value.filter(_.nonEmpty).toList.map(name -> _)

  private def encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private final class LiveOAuth[F[_]](runner: EndpointRunner[F]) extends OAuth[F]:

  def metadata: F[OAuthServerMetadata] =
    runner.run(OAuth.metadataEndpoint)

  def token(
      grantType: OAuthGrantType,
      clientId: String,
      clientSecret: String,
      code: Option[String],
      redirectUri: Option[String],
      scope: Option[List[String]],
      codeVerifier: Option[String]
  ): F[OAuthToken] =
    runner.run(
      OAuth.tokenEndpoint(grantType, clientId, clientSecret, code, redirectUri, scope, codeVerifier)
    )

  def revoke(token: String, clientId: String, clientSecret: String): F[Unit] =
    runner.run(OAuth.revokeEndpoint(token, clientId, clientSecret))

  def userinfo: F[UserInfo] =
    runner.run(OAuth.userinfoEndpoint)

  def mapK[G[_]: MonadThrow](transform: F ~> G): OAuth[G] =
    new LiveOAuth[G](runner.mapK(transform))
