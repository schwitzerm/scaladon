package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Profile as UserProfile}

/** The "Profile" section (Mastodon 4.6+): the current user's editable profile.
  *
  * `GET` returns the raw source values; `PATCH` replaces them. The update body is
  * `application/x-www-form-urlencoded` — that is what the API documents, and unlike JSON it lets a
  * caller clear a field by sending an empty string. Absent options are omitted, never sent as
  * `null`, and `fields` is indexed with `fields_attributes[:index][name|value]`.
  */
trait Profile[F[_]]:
  def get: F[UserProfile]

  def update(
      displayName: Option[String] = None,
      note: Option[String] = None,
      avatarDescription: Option[String] = None,
      headerDescription: Option[String] = None,
      locked: Option[Boolean] = None,
      bot: Option[Boolean] = None,
      discoverable: Option[Boolean] = None,
      hideCollections: Option[Boolean] = None,
      indexable: Option[Boolean] = None,
      showMedia: Option[Boolean] = None,
      showMediaReplies: Option[Boolean] = None,
      showFeatured: Option[Boolean] = None,
      attributionDomains: List[String] = Nil,
      fields: List[(String, String)] = Nil
  ): F[UserProfile]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Profile[G]

object Profile:

  def live[F[_]](runner: EndpointRunner[F]): Profile[F] =
    new LiveProfile[F](runner)

  /** `GET /api/v1/profile`. */
  def getEndpoint: Endpoint[UserProfile] =
    Endpoint.get[UserProfile]("/api/v1/profile")

  /** `PATCH /api/v1/profile` — form-encoded, absent options omitted. */
  def updateEndpoint(
      displayName: Option[String] = None,
      note: Option[String] = None,
      avatarDescription: Option[String] = None,
      headerDescription: Option[String] = None,
      locked: Option[Boolean] = None,
      bot: Option[Boolean] = None,
      discoverable: Option[Boolean] = None,
      hideCollections: Option[Boolean] = None,
      indexable: Option[Boolean] = None,
      showMedia: Option[Boolean] = None,
      showMediaReplies: Option[Boolean] = None,
      showFeatured: Option[Boolean] = None,
      attributionDomains: List[String] = Nil,
      fields: List[(String, String)] = Nil
  ): Endpoint[UserProfile] =
    Endpoint.patchForm[UserProfile]("/api/v1/profile", updateFields(
      displayName,
      note,
      avatarDescription,
      headerDescription,
      locked,
      bot,
      discoverable,
      hideCollections,
      indexable,
      showMedia,
      showMediaReplies,
      showFeatured,
      attributionDomains,
      fields
    ))

  private[api] def updateFields(
      displayName: Option[String],
      note: Option[String],
      avatarDescription: Option[String],
      headerDescription: Option[String],
      locked: Option[Boolean],
      bot: Option[Boolean],
      discoverable: Option[Boolean],
      hideCollections: Option[Boolean],
      indexable: Option[Boolean],
      showMedia: Option[Boolean],
      showMediaReplies: Option[Boolean],
      showFeatured: Option[Boolean],
      attributionDomains: List[String],
      fields: List[(String, String)]
  ): List[(String, String)] =
    Accounts.param("display_name", displayName) ++
      Accounts.param("note", note) ++
      Accounts.param("avatar_description", avatarDescription) ++
      Accounts.param("header_description", headerDescription) ++
      Accounts.param("locked", locked) ++
      Accounts.param("bot", bot) ++
      Accounts.param("discoverable", discoverable) ++
      Accounts.param("hide_collections", hideCollections) ++
      Accounts.param("indexable", indexable) ++
      Accounts.param("show_media", showMedia) ++
      Accounts.param("show_media_replies", showMediaReplies) ++
      Accounts.param("show_featured", showFeatured) ++
      attributionDomains.map("attribution_domains[]" -> _) ++
      fields.zipWithIndex.flatMap { case ((name, value), index) =>
        List(
          s"fields_attributes[$index][name]"  -> name,
          s"fields_attributes[$index][value]" -> value
        )
      }

private final class LiveProfile[F[_]](runner: EndpointRunner[F]) extends Profile[F]:

  def get: F[UserProfile] =
    runner.run(Profile.getEndpoint)

  def update(
      displayName: Option[String],
      note: Option[String],
      avatarDescription: Option[String],
      headerDescription: Option[String],
      locked: Option[Boolean],
      bot: Option[Boolean],
      discoverable: Option[Boolean],
      hideCollections: Option[Boolean],
      indexable: Option[Boolean],
      showMedia: Option[Boolean],
      showMediaReplies: Option[Boolean],
      showFeatured: Option[Boolean],
      attributionDomains: List[String],
      fields: List[(String, String)]
  ): F[UserProfile] =
    runner.run(
      Profile.updateEndpoint(
        displayName,
        note,
        avatarDescription,
        headerDescription,
        locked,
        bot,
        discoverable,
        hideCollections,
        indexable,
        showMedia,
        showMediaReplies,
        showFeatured,
        attributionDomains,
        fields
      )
    )

  def mapK[G[_]: MonadThrow](transform: F ~> G): Profile[G] =
    new LiveProfile[G](runner.mapK(transform))
