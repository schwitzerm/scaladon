package ca.schwitzer.scaladon.domain

import java.time.Instant

/** A hashtag in the minimal shape Collections embed.
  *
  * Same rationale as [[Account]]: only the fields this adapter consumes are modelled, so the older
  * 2017 tag shape does not force itself on the new surface.
  */
final case class ShallowTag(name: String, url: String)

/** Consent state of an account featured in a [[Collection]].
  *
  * An enum with a total `fromWire` instead of a `String`, matching [[Visibility]]:
  * unknown values fail decoding loudly rather than leaking an unhandled string into callers.
  */
enum CollectionItemState:
  case Pending, Accepted, Rejected, Revoked

  def wire: String = this match
    case Pending  => "pending"
    case Accepted => "accepted"
    case Rejected => "rejected"
    case Revoked  => "revoked"

object CollectionItemState:
  def fromWire(value: String): Option[CollectionItemState] = value match
    case "pending"  => Some(Pending)
    case "accepted" => Some(Accepted)
    case "rejected" => Some(Rejected)
    case "revoked"  => Some(Revoked)
    case _          => None

/** One account featured in a [[Collection]].
  *
  * `accountId` is nullable on the wire: a featured account may have been deleted after being added.
  */
final case class CollectionItem(
    id: Id,
    accountId: Option[Id],
    state: CollectionItemState,
    createdAt: Instant
)

/** A curated list of accounts a user recommends to others (Mastodon 4.6+, API version 10).
  *
  * `url` and `language` are nullable on the wire, and an empty `description` is normalised to
  * `None` in the codec. `items` carries the embedded [[CollectionItem]]s; `itemCount` is the
  * server's total and can differ from `items.length` when the server trims the page.
  */
final case class Collection(
    id: Id,
    accountId: Id,
    uri: String,
    url: Option[String],
    name: String,
    description: Option[String],
    language: Option[String],
    local: Boolean,
    sensitive: Boolean,
    discoverable: Boolean,
    tag: Option[ShallowTag],
    itemCount: Int,
    items: List[CollectionItem],
    createdAt: Instant,
    updatedAt: Instant
)

/** `GET /api/v1/collections/:id` answers with the owner plus every featured account alongside the
  * Collection, so the caller does not have to resolve each `account_id` separately.
  */
final case class CollectionWithAccounts(accounts: List[Account], collection: Collection)
