package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}
import io.circe.{Decoder, Json}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{ApiError, Collection, CollectionItem, CollectionWithAccounts, Id}
import ca.schwitzer.scaladon.http.HttpMethod

/** The "Collections" section (Mastodon 4.6+, API version 10), as an algebra of values.
  *
  * A collection is a user-curated list of recommended accounts. Adding someone can require their
  * consent, so an item carries a [[ca.schwitzer.scaladon.domain.CollectionItemState]] rather than
  * appearing or not. `F` is the effect; failures are raised as `ApiException(ApiError)`.
  */
trait Collections[F[_]]:

  /** `POST /api/v1/collections`; returns the created collection. */
  def create(
      name: String,
      description: Option[String] = None,
      language: Option[String] = None,
      tagName: Option[String] = None,
      sensitive: Option[Boolean] = None,
      discoverable: Option[Boolean] = None,
      accountIds: List[Id] = Nil
  ): F[Collection]

  /** `GET /api/v1/collections/{id}`; the owner and featured accounts come alongside the collection. */
  def get(id: Id): F[CollectionWithAccounts]

  /** `PATCH /api/v1/collections/{id}`; only the options supplied are sent. */
  def update(
      id: Id,
      name: Option[String] = None,
      description: Option[String] = None,
      language: Option[String] = None,
      tagName: Option[String] = None,
      sensitive: Option[Boolean] = None,
      discoverable: Option[Boolean] = None
  ): F[Collection]

  /** `DELETE /api/v1/collections/{id}`. */
  def delete(id: Id): F[Unit]

  /** `POST /api/v1/collections/{collectionId}/items`; returns the new item and its consent state. */
  def addItem(collectionId: Id, accountId: Id): F[CollectionItem]

  /** `DELETE /api/v1/collections/{collectionId}/items/{itemId}` — by the collection's owner. */
  def removeItem(collectionId: Id, itemId: Id): F[Unit]

  /** `POST /api/v1/collections/{collectionId}/items/{itemId}/revoke` — by the featured account. */
  def revokeItem(collectionId: Id, itemId: Id): F[Unit]

  /** `GET /api/v1/accounts/{accountId}/collections`, newest first, paginated by limit/offset. */
  def forAccount(
      accountId: Id,
      limit: Option[Int] = None,
      offset: Option[Int] = None
  ): F[List[Collection]]

  /** `GET /api/v1/accounts/{accountId}/in_collections` — collections featuring that account. */
  def inCollections(
      accountId: Id,
      limit: Option[Int] = None,
      offset: Option[Int] = None
  ): F[List[Collection]]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Collections[G]

object Collections:

  def live[F[_]](runner: EndpointRunner[F]): Collections[F] =
    new LiveCollections[F](runner)

  /** `POST /api/v1/collections`. The response wraps the collection under `collection`. */
  def createEndpoint(
      name: String,
      description: Option[String],
      language: Option[String],
      tagName: Option[String],
      sensitive: Option[Boolean],
      discoverable: Option[Boolean],
      accountIds: List[Id]
  ): Endpoint[Collection] =
    Endpoint(
      HttpMethod.POST,
      "/api/v1/collections",
      Nil,
      Some(
        createBody(name, description, language, tagName, sensitive, discoverable, accountIds)
      ),
      authenticated = true,
      decode = field[Collection]("collection")
    )

  /** `GET /api/v1/collections/{id}`. */
  def getEndpoint(id: Id): Endpoint[CollectionWithAccounts] =
    Endpoint.get[CollectionWithAccounts](s"/api/v1/collections/${id.value}")

  /** `PATCH /api/v1/collections/{id}`; the response wraps the collection under `collection`. */
  def updateEndpoint(
      id: Id,
      name: Option[String],
      description: Option[String],
      language: Option[String],
      tagName: Option[String],
      sensitive: Option[Boolean],
      discoverable: Option[Boolean]
  ): Endpoint[Collection] =
    Endpoint(
      HttpMethod.PATCH,
      s"/api/v1/collections/${id.value}",
      Nil,
      Some(updateBody(name, description, language, tagName, sensitive, discoverable)),
      authenticated = true,
      decode = field[Collection]("collection")
    )

  /** `DELETE /api/v1/collections/{id}`. */
  def deleteEndpoint(id: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, s"/api/v1/collections/${id.value}")

  /** `POST /api/v1/collections/{collectionId}/items`; wrapped under `collection_item`. */
  def addItemEndpoint(collectionId: Id, accountId: Id): Endpoint[CollectionItem] =
    Endpoint(
      HttpMethod.POST,
      s"/api/v1/collections/${collectionId.value}/items",
      Nil,
      Some(Json.obj("account_id" -> Json.fromString(accountId.value))),
      authenticated = true,
      decode = field[CollectionItem]("collection_item")
    )

  /** `DELETE /api/v1/collections/{collectionId}/items/{itemId}`. */
  def removeItemEndpoint(collectionId: Id, itemId: Id): Endpoint[Unit] =
    Endpoint.unit(
      HttpMethod.DELETE,
      s"/api/v1/collections/${collectionId.value}/items/${itemId.value}"
    )

  /** `POST /api/v1/collections/{collectionId}/items/{itemId}/revoke` (no body). */
  def revokeItemEndpoint(collectionId: Id, itemId: Id): Endpoint[Unit] =
    Endpoint.unit(
      HttpMethod.POST,
      s"/api/v1/collections/${collectionId.value}/items/${itemId.value}/revoke"
    )

  /** `GET /api/v1/accounts/{accountId}/collections`; wrapped under `collections`. */
  def forAccountEndpoint(
      accountId: Id,
      limit: Option[Int],
      offset: Option[Int]
  ): Endpoint[List[Collection]] =
    collectionListEndpoint(
      s"/api/v1/accounts/${accountId.value}/collections",
      pageQuery(limit, offset)
    )

  /** `GET /api/v1/accounts/{accountId}/in_collections`; wrapped under `collections`. */
  def inCollectionsEndpoint(
      accountId: Id,
      limit: Option[Int],
      offset: Option[Int]
  ): Endpoint[List[Collection]] =
    collectionListEndpoint(
      s"/api/v1/accounts/${accountId.value}/in_collections",
      pageQuery(limit, offset)
    )

  private def createBody(
      name: String,
      description: Option[String],
      language: Option[String],
      tagName: Option[String],
      sensitive: Option[Boolean],
      discoverable: Option[Boolean],
      accountIds: List[Id]
  ): Json =
    val required = List("name" -> Json.fromString(name))
    val accountIdsJson =
      if accountIds.isEmpty then Nil
      else List("account_ids" -> Json.arr(accountIds.map(id => Json.fromString(id.value))*))
    Json.fromFields(
      required ++ optionFields(None, description, language, tagName, sensitive, discoverable) ++
        accountIdsJson
    )

  private def updateBody(
      name: Option[String],
      description: Option[String],
      language: Option[String],
      tagName: Option[String],
      sensitive: Option[Boolean],
      discoverable: Option[Boolean]
  ): Json =
    Json.fromFields(optionFields(name, description, language, tagName, sensitive, discoverable))

  /** Absent options are omitted, never emitted as JSON `null`. */
  private def optionFields(
      name: Option[String],
      description: Option[String],
      language: Option[String],
      tagName: Option[String],
      sensitive: Option[Boolean],
      discoverable: Option[Boolean]
  ): List[(String, Json)] =
    name.toList.map(value => "name" -> Json.fromString(value)) ++
      description.toList.map(value => "description" -> Json.fromString(value)) ++
      language.toList.map(value => "language" -> Json.fromString(value)) ++
      tagName.toList.map(value => "tag_name" -> Json.fromString(value)) ++
      sensitive.toList.map(value => "sensitive" -> Json.fromBoolean(value)) ++
      discoverable.toList.map(value => "discoverable" -> Json.fromBoolean(value))

  private def pageQuery(limit: Option[Int], offset: Option[Int]): List[(String, String)] =
    limit.toList.map(value => "limit" -> value.toString) ++
      offset.toList.map(value => "offset" -> value.toString)

  /** Collection lists are wrapped under `collections`, so the entity-list decoder is applied there. */
  private def collectionListEndpoint(
      path: String,
      query: List[(String, String)]
  ): Endpoint[List[Collection]] =
    Endpoint(
      HttpMethod.GET,
      path,
      query,
      None,
      authenticated = true,
      decode = field[List[Collection]]("collections")
    )

  /** Decode a value the API wraps under a named key, keeping the plain entity decoder reusable. */
  private def field[A](name: String)(using Decoder[A]): Json => Either[ApiError, A] =
    json =>
      json.hcursor.downField(name).as[A].left.map(failure => ApiError.Decode(failure.message))

private final class LiveCollections[F[_]](runner: EndpointRunner[F]) extends Collections[F]:

  def create(
      name: String,
      description: Option[String],
      language: Option[String],
      tagName: Option[String],
      sensitive: Option[Boolean],
      discoverable: Option[Boolean],
      accountIds: List[Id]
  ): F[Collection] =
    runner.run(
      Collections.createEndpoint(
        name,
        description,
        language,
        tagName,
        sensitive,
        discoverable,
        accountIds
      )
    )

  def get(id: Id): F[CollectionWithAccounts] =
    runner.run(Collections.getEndpoint(id))

  def update(
      id: Id,
      name: Option[String],
      description: Option[String],
      language: Option[String],
      tagName: Option[String],
      sensitive: Option[Boolean],
      discoverable: Option[Boolean]
  ): F[Collection] =
    runner.run(
      Collections.updateEndpoint(
        id,
        name,
        description,
        language,
        tagName,
        sensitive,
        discoverable
      )
    )

  def delete(id: Id): F[Unit] =
    runner.run(Collections.deleteEndpoint(id))

  def addItem(collectionId: Id, accountId: Id): F[CollectionItem] =
    runner.run(Collections.addItemEndpoint(collectionId, accountId))

  def removeItem(collectionId: Id, itemId: Id): F[Unit] =
    runner.run(Collections.removeItemEndpoint(collectionId, itemId))

  def revokeItem(collectionId: Id, itemId: Id): F[Unit] =
    runner.run(Collections.revokeItemEndpoint(collectionId, itemId))

  def forAccount(
      accountId: Id,
      limit: Option[Int],
      offset: Option[Int]
  ): F[List[Collection]] =
    runner.run(Collections.forAccountEndpoint(accountId, limit, offset))

  def inCollections(
      accountId: Id,
      limit: Option[Int],
      offset: Option[Int]
  ): F[List[Collection]] =
    runner.run(Collections.inCollectionsEndpoint(accountId, limit, offset))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Collections[G] =
    new LiveCollections[G](runner.mapK(transform))
