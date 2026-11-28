package ca.schwitzer.scaladon.domain

import java.time.Instant

/** The token grants Mastodon implements since the password grant was removed (4.4+).
  *
  * `authorization_code` is the user flow (with PKCE where supported); `client_credentials` is the
  * app-only flow. There is deliberately no password case: the server no longer accepts it.
  */
enum OAuthGrantType(val wire: String):
  case AuthorizationCode extends OAuthGrantType("authorization_code")
  case ClientCredentials extends OAuthGrantType("client_credentials")

object OAuthGrantType:
  def fromWire(value: String): Option[OAuthGrantType] = value match
    case "authorization_code" => Some(AuthorizationCode)
    case "client_credentials" => Some(ClientCredentials)
    case _                    => None

/** `GET /.well-known/oauth-authorization-server` (RFC 8414, Mastodon 4.3+).
  *
  * Clients discover endpoints, scopes and supported PKCE methods here instead of guessing from the
  * instance version. The non-standard `app_registration_endpoint` points at `POST /api/v1/apps`
  * because Mastodon does not implement RFC 7591 dynamic client registration.
  *
  * Optional fields stay `Option`; list-valued fields default to `Nil` when a server omits them, so
  * capability checks read as `metadata.scopesSupported.contains("profile")`.
  */
final case class OAuthServerMetadata(
    issuer: String,
    serviceDocumentation: Option[String],
    authorizationEndpoint: String,
    tokenEndpoint: String,
    appRegistrationEndpoint: Option[String],
    revocationEndpoint: Option[String],
    userinfoEndpoint: Option[String],
    scopesSupported: List[String],
    responseTypesSupported: List[String],
    responseModesSupported: List[String],
    grantTypesSupported: List[String],
    codeChallengeMethodsSupported: List[String],
    tokenEndpointAuthMethodsSupported: List[String],
    promptValuesSupported: List[String]
)

/** `POST /oauth/token` response (the Token entity).
  *
  * `createdAt` is the token's creation time. The wire field is a UNIX timestamp in seconds, not an
  * RFC 3339 string like every other timestamp in the API; the codec accepts both but the number is
  * what Mastodon sends. `expiresIn` is optional because Mastodon's tokens currently do not expire.
  */
final case class OAuthToken(
    accessToken: String,
    tokenType: String,
    scope: List[String],
    createdAt: Instant,
    expiresIn: Option[Long]
)

/** The OIDC claims `GET /oauth/userinfo` returns (Mastodon 4.4+, requires the `profile` scope).
  *
  * `subject` is the ActivityPub actor URI rather than the account id, because ids are only unique
  * per instance. The remaining claims describe the profile as OIDC consumers expect them.
  */
final case class UserInfo(
    issuer: String,
    subject: String,
    name: String,
    preferredUsername: String,
    profile: String,
    picture: String
)
