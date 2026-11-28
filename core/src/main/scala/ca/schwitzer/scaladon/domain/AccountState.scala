package ca.schwitzer.scaladon.domain

/** Why a `CredentialAccount` is not functional, as a typed code (Mastodon 5.0).
  *
  * The server documents the set as extensible ("additional reasons can be added in the future"),
  * but an unknown value is treated as a decode failure like every other enum in this tree rather
  * than being silently dropped. Clients that must tolerate server additions should catch the
  * `ApiError.Decode` from the call, not get a lossy default.
  */
enum AccountStateError:
  case PendingConfirmation
  case PendingApproval
  case AccountDeleted
  case AccountSuspended
  case Memorial
  case LoginDisabled
  case TwoFactorRequired

object AccountStateError:
  def fromWire(value: String): Option[AccountStateError] = value match
    case "pending_confirmation" => Some(PendingConfirmation)
    case "pending_approval"     => Some(PendingApproval)
    case "account_deleted"      => Some(AccountDeleted)
    case "account_suspended"    => Some(AccountSuspended)
    case "memorial"             => Some(Memorial)
    case "login_disabled"       => Some(LoginDisabled)
    case "2fa_required"         => Some(TwoFactorRequired)
    case _                      => None

/** The 5.0 sign-up state, returned as `state` on `CredentialAccount`.
  *
  * `functional` is the flag callers should branch on; `confirmed`/`approved` explain why it is
  * false, and `error`/`errorCode` carry the localized message and its wire code. Both error fields
  * are absent for a functional account.
  */
final case class AccountState(
    functional: Boolean,
    confirmed: Boolean,
    approved: Boolean,
    error: Option[String],
    errorCode: Option[AccountStateError]
)
