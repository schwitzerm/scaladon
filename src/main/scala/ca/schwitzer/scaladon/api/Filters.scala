package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}
import io.circe.Decoder

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{
  ApiError,
  Filter,
  FilterAction,
  FilterContext,
  FilterKeyword,
  FilterStatus,
  Id
}
import ca.schwitzer.scaladon.http.HttpMethod

/** One `keywords_attributes[]` entry on filter create/update.
  *
  * Create supplies `keyword` and optionally `wholeWord`; update identifies an existing entry by
  * `id` to replace it, or sets `destroy` to remove it (`keyword` is then not needed). Fields that
  * are `None` are omitted from the form body, never sent as empty values (legacy #10).
  */
final case class FilterKeywordAttribute(
    keyword: Option[String] = None,
    wholeWord: Option[Boolean] = None,
    id: Option[Id] = None,
    destroy: Boolean = false
)

/** The server-side "Filters" section (API version 4+), as an algebra of values.
  *
  * A filter group holds keywords and statuses and applies one [[FilterAction]] in each
  * [[FilterContext]]. Matching happens on the server since Mastodon 4.0; clients read
  * `Status.filtered` rather than matching locally. The deprecated `/api/v1/filters` endpoints are
  * not modelled: they only exist for older clients and their DELETE removes a keyword, not the
  * group.
  *
  * `F` is the effect; expected failures are raised as `ApiException(ApiError)`.
  */
trait Filters[F[_]]:

  /** `GET /api/v2/filters`; every filter group owned by the current user. */
  def list(): F[List[Filter]]

  /** `GET /api/v2/filters/{id}`; `404` for a group the user does not own. */
  def get(id: Id): F[Filter]

  /** `POST /api/v2/filters` (form); `context` must not be empty, `422` otherwise. */
  def create(
      title: String,
      context: List[FilterContext],
      filterAction: Option[FilterAction] = None,
      expiresIn: Option[Int] = None,
      keywords: List[FilterKeywordAttribute] = Nil
  ): F[Filter]

  /** `PUT /api/v2/filters/{id}` (form); only the options supplied are sent.
    *
    * `Some(Nil)` for `context` sends no `context[]` entries at all, so the server keeps the
    * current value; a filter group cannot have an empty context.
    */
  def update(
      id: Id,
      title: Option[String] = None,
      context: Option[List[FilterContext]] = None,
      filterAction: Option[FilterAction] = None,
      expiresIn: Option[Int] = None,
      keywords: List[FilterKeywordAttribute] = Nil
  ): F[Filter]

  /** `DELETE /api/v2/filters/{id}`. */
  def delete(id: Id): F[Unit]

  /** `GET /api/v2/filters/{filterId}/keywords`. */
  def keywords(filterId: Id): F[List[FilterKeyword]]

  /** `GET /api/v2/filters/keywords/{id}`. */
  def getKeyword(id: Id): F[FilterKeyword]

  /** `POST /api/v2/filters/{filterId}/keywords` (form); `wholeWord` is omitted when absent. */
  def addKeyword(
      filterId: Id,
      keyword: String,
      wholeWord: Option[Boolean] = None
  ): F[FilterKeyword]

  /** `PUT /api/v2/filters/keywords/{id}` (form). */
  def updateKeyword(
      id: Id,
      keyword: String,
      wholeWord: Option[Boolean] = None
  ): F[FilterKeyword]

  /** `DELETE /api/v2/filters/keywords/{id}`. */
  def deleteKeyword(id: Id): F[Unit]

  /** `GET /api/v2/filters/{filterId}/statuses`. */
  def statuses(filterId: Id): F[List[FilterStatus]]

  /** `GET /api/v2/filters/statuses/{id}`. */
  def getStatus(id: Id): F[FilterStatus]

  /** `POST /api/v2/filters/{filterId}/statuses` (form). */
  def addStatus(filterId: Id, statusId: Id): F[FilterStatus]

  /** `DELETE /api/v2/filters/statuses/{id}`. */
  def deleteStatus(id: Id): F[Unit]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Filters[G]

object Filters:

  def live[F[_]](runner: EndpointRunner[F]): Filters[F] =
    new LiveFilters[F](runner)

  def listEndpoint: Endpoint[List[Filter]] =
    Endpoint.get[List[Filter]]("/api/v2/filters")

  def getEndpoint(id: Id): Endpoint[Filter] =
    Endpoint.get[Filter](s"/api/v2/filters/${id.value}")

  def createEndpoint(
      title: String,
      context: List[FilterContext],
      filterAction: Option[FilterAction],
      expiresIn: Option[Int],
      keywords: List[FilterKeywordAttribute]
  ): Endpoint[Filter] =
    Endpoint.postForm[Filter](
      "/api/v2/filters",
      List("title" -> title) ++
        repeatedContext(context) ++
        filterAction.toList.map(action => "filter_action" -> action.wire) ++
        expiresIn.toList.map(seconds => "expires_in" -> seconds.toString) ++
        keywordFields(keywords)
    )

  def updateEndpoint(
      id: Id,
      title: Option[String],
      context: Option[List[FilterContext]],
      filterAction: Option[FilterAction],
      expiresIn: Option[Int],
      keywords: List[FilterKeywordAttribute]
  ): Endpoint[Filter] =
    putForm[Filter](
      s"/api/v2/filters/${id.value}",
      title.toList.map(value => "title" -> value) ++
        context.toList.flatMap(repeatedContext) ++
        filterAction.toList.map(action => "filter_action" -> action.wire) ++
        expiresIn.toList.map(seconds => "expires_in" -> seconds.toString) ++
        keywordFields(keywords)
    )

  def deleteEndpoint(id: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, s"/api/v2/filters/${id.value}")

  def keywordsEndpoint(filterId: Id): Endpoint[List[FilterKeyword]] =
    Endpoint.get[List[FilterKeyword]](s"/api/v2/filters/${filterId.value}/keywords")

  def getKeywordEndpoint(id: Id): Endpoint[FilterKeyword] =
    Endpoint.get[FilterKeyword](s"/api/v2/filters/keywords/${id.value}")

  def addKeywordEndpoint(
      filterId: Id,
      keyword: String,
      wholeWord: Option[Boolean]
  ): Endpoint[FilterKeyword] =
    Endpoint.postForm[FilterKeyword](
      s"/api/v2/filters/${filterId.value}/keywords",
      List("keyword" -> keyword) ++ wholeWord.toList.map(on => "whole_word" -> on.toString)
    )

  def updateKeywordEndpoint(
      id: Id,
      keyword: String,
      wholeWord: Option[Boolean]
  ): Endpoint[FilterKeyword] =
    putForm[FilterKeyword](
      s"/api/v2/filters/keywords/${id.value}",
      List("keyword" -> keyword) ++ wholeWord.toList.map(on => "whole_word" -> on.toString)
    )

  def deleteKeywordEndpoint(id: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, s"/api/v2/filters/keywords/${id.value}")

  def statusesEndpoint(filterId: Id): Endpoint[List[FilterStatus]] =
    Endpoint.get[List[FilterStatus]](s"/api/v2/filters/${filterId.value}/statuses")

  def getStatusEndpoint(id: Id): Endpoint[FilterStatus] =
    Endpoint.get[FilterStatus](s"/api/v2/filters/statuses/${id.value}")

  def addStatusEndpoint(filterId: Id, statusId: Id): Endpoint[FilterStatus] =
    Endpoint.postForm[FilterStatus](
      s"/api/v2/filters/${filterId.value}/statuses",
      List("status_id" -> statusId.value)
    )

  def deleteStatusEndpoint(id: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, s"/api/v2/filters/statuses/${id.value}")

  /** `context[]` is repeated once per entry; the server rejects a blank list with `422`. */
  private def repeatedContext(context: List[FilterContext]): List[(String, String)] =
    context.map(value => "context[]" -> value.wire)

  /** Rack builds one hash per keyword for `keywords_attributes[][...]`: the first field of an entry
    * opens a new hash and the following fields merge into it, so the field order is load-bearing.
    */
  private def keywordFields(keywords: List[FilterKeywordAttribute]): List[(String, String)] =
    keywords.flatMap { attribute =>
      attribute.id.toList.map(id => "keywords_attributes[][id]" -> id.value) ++
        attribute.keyword.toList.map(value => "keywords_attributes[][keyword]" -> value) ++
        attribute.wholeWord.toList.map(on => "keywords_attributes[][whole_word]" -> on.toString) ++
        (if attribute.destroy then List("keywords_attributes[][_destroy]" -> "true") else Nil)
    }

  /** `Endpoint` has no `putForm` constructor yet (the transport lane owns that file), so a form
    * `PUT` is described directly; decoding behaves exactly like `Endpoint.postForm`.
    */
  private def putForm[A: Decoder](
      path: String,
      fields: List[(String, String)]
  ): Endpoint[A] =
    Endpoint(
      HttpMethod.PUT,
      path,
      Nil,
      None,
      authenticated = true,
      decode = json =>
        Decoder[A].decodeJson(json).left.map(failure => ApiError.Decode(failure.message)),
      form = Some(fields)
    )

private final class LiveFilters[F[_]](runner: EndpointRunner[F]) extends Filters[F]:

  def list(): F[List[Filter]] =
    runner.run(Filters.listEndpoint)

  def get(id: Id): F[Filter] =
    runner.run(Filters.getEndpoint(id))

  def create(
      title: String,
      context: List[FilterContext],
      filterAction: Option[FilterAction],
      expiresIn: Option[Int],
      keywords: List[FilterKeywordAttribute]
  ): F[Filter] =
    runner.run(Filters.createEndpoint(title, context, filterAction, expiresIn, keywords))

  def update(
      id: Id,
      title: Option[String],
      context: Option[List[FilterContext]],
      filterAction: Option[FilterAction],
      expiresIn: Option[Int],
      keywords: List[FilterKeywordAttribute]
  ): F[Filter] =
    runner.run(
      Filters.updateEndpoint(id, title, context, filterAction, expiresIn, keywords)
    )

  def delete(id: Id): F[Unit] =
    runner.run(Filters.deleteEndpoint(id))

  def keywords(filterId: Id): F[List[FilterKeyword]] =
    runner.run(Filters.keywordsEndpoint(filterId))

  def getKeyword(id: Id): F[FilterKeyword] =
    runner.run(Filters.getKeywordEndpoint(id))

  def addKeyword(
      filterId: Id,
      keyword: String,
      wholeWord: Option[Boolean]
  ): F[FilterKeyword] =
    runner.run(Filters.addKeywordEndpoint(filterId, keyword, wholeWord))

  def updateKeyword(
      id: Id,
      keyword: String,
      wholeWord: Option[Boolean]
  ): F[FilterKeyword] =
    runner.run(Filters.updateKeywordEndpoint(id, keyword, wholeWord))

  def deleteKeyword(id: Id): F[Unit] =
    runner.run(Filters.deleteKeywordEndpoint(id))

  def statuses(filterId: Id): F[List[FilterStatus]] =
    runner.run(Filters.statusesEndpoint(filterId))

  def getStatus(id: Id): F[FilterStatus] =
    runner.run(Filters.getStatusEndpoint(id))

  def addStatus(filterId: Id, statusId: Id): F[FilterStatus] =
    runner.run(Filters.addStatusEndpoint(filterId, statusId))

  def deleteStatus(id: Id): F[Unit] =
    runner.run(Filters.deleteStatusEndpoint(id))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Filters[G] =
    new LiveFilters[G](runner.mapK(transform))
