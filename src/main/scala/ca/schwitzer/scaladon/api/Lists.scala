package ca.schwitzer.scaladon.api

import cats.{MonadThrow, ~>}

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{Account, Id, MastodonList, RepliesPolicy}
import ca.schwitzer.scaladon.http.HttpMethod

/** The "Lists" section: curated lists of followed accounts and their membership.
  *
  * Every call needs a user token (`read:lists`/`write:lists`); a list owned by someone else answers
  * `404`, which surfaces as `ApiError.Http` with `isNotFound`. Writes are form-encoded, as the API
  * documents them, and membership changes send the accounts as repeated `account_ids[]` keys.
  * Absent options are omitted rather than sent as `null`.
  *
  * The type is [[ca.schwitzer.scaladon.domain.MastodonList]] because `List` here would shadow
  * `scala.List` in signatures such as `F[List[MastodonList]]`.
  */
trait Lists[F[_]]:

  /** `GET /api/v1/lists` — the lists the authenticated user owns. */
  def list(): F[List[MastodonList]]

  /** `GET /api/v1/lists/{id}`. */
  def get(id: Id): F[MastodonList]

  /** `POST /api/v1/lists`. `title` is required; the server defaults `replies_policy` to `list`. */
  def create(
      title: String,
      repliesPolicy: Option[RepliesPolicy] = None,
      exclusive: Option[Boolean] = None
  ): F[MastodonList]

  /** `PUT /api/v1/lists/{id}` — the server expects the full title, not a partial patch. */
  def update(
      id: Id,
      title: String,
      repliesPolicy: Option[RepliesPolicy] = None,
      exclusive: Option[Boolean] = None
  ): F[MastodonList]

  /** `DELETE /api/v1/lists/{id}`. */
  def delete(id: Id): F[Unit]

  /** `GET /api/v1/lists/{id}/accounts`, paginated; `limit = 0` asks for every member. */
  def accounts(
      id: Id,
      maxId: Option[Id] = None,
      sinceId: Option[Id] = None,
      minId: Option[Id] = None,
      limit: Option[Int] = None
  ): F[List[Account]]

  /** `POST /api/v1/lists/{id}/accounts`; every account must already be followed. */
  def addAccounts(id: Id, accountIds: List[Id]): F[Unit]

  /** `DELETE /api/v1/lists/{id}/accounts`; removing a non-member is not an error. */
  def removeAccounts(id: Id, accountIds: List[Id]): F[Unit]

  def mapK[G[_]: MonadThrow](transform: F ~> G): Lists[G]

object Lists:

  def live[F[_]](runner: EndpointRunner[F]): Lists[F] =
    new LiveLists[F](runner)

  /** `GET /api/v1/lists`; a bare JSON array of lists. */
  def listEndpoint: Endpoint[List[MastodonList]] =
    Endpoint.get[List[MastodonList]]("/api/v1/lists")

  /** `GET /api/v1/lists/{id}`. */
  def getEndpoint(id: Id): Endpoint[MastodonList] =
    Endpoint.get[MastodonList](s"/api/v1/lists/${id.value}")

  /** `POST /api/v1/lists`; form-encoded. */
  def createEndpoint(
      title: String,
      repliesPolicy: Option[RepliesPolicy],
      exclusive: Option[Boolean]
  ): Endpoint[MastodonList] =
    Endpoint.postForm[MastodonList](
      "/api/v1/lists",
      writeFields(title, repliesPolicy, exclusive)
    )

  /** `PUT /api/v1/lists/{id}`; form-encoded, with the full `title`. */
  def updateEndpoint(
      id: Id,
      title: String,
      repliesPolicy: Option[RepliesPolicy],
      exclusive: Option[Boolean]
  ): Endpoint[MastodonList] =
    Endpoint
      .postForm[MastodonList](
        s"/api/v1/lists/${id.value}",
        writeFields(title, repliesPolicy, exclusive)
      )
      .copy(method = HttpMethod.PUT)

  /** `DELETE /api/v1/lists/{id}`. */
  def deleteEndpoint(id: Id): Endpoint[Unit] =
    Endpoint.unit(HttpMethod.DELETE, s"/api/v1/lists/${id.value}")

  /** `GET /api/v1/lists/{id}/accounts`, cursor-paginated. */
  def accountsEndpoint(
      id: Id,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): Endpoint[List[Account]] =
    Endpoint.get[List[Account]](
      s"/api/v1/lists/${id.value}/accounts",
      query =
        maxId.toList.map(value => "max_id" -> value.value) ++
          sinceId.toList.map(value => "since_id" -> value.value) ++
          minId.toList.map(value => "min_id" -> value.value) ++
          limit.toList.map(value => "limit" -> value.toString)
    )

  /** `POST /api/v1/lists/{id}/accounts` — repeated `account_ids[]`, empty `{}` response. */
  def addAccountsEndpoint(id: Id, accountIds: List[Id]): Endpoint[Unit] =
    Endpoint.unitForm(HttpMethod.POST, s"/api/v1/lists/${id.value}/accounts", memberFields(accountIds))

  /** `DELETE /api/v1/lists/{id}/accounts` — repeated `account_ids[]` in a form body. */
  def removeAccountsEndpoint(id: Id, accountIds: List[Id]): Endpoint[Unit] =
    Endpoint.unitForm(HttpMethod.DELETE, s"/api/v1/lists/${id.value}/accounts", memberFields(accountIds))

  private def writeFields(
      title: String,
      repliesPolicy: Option[RepliesPolicy],
      exclusive: Option[Boolean]
  ): List[(String, String)] =
    List("title" -> title) ++
      repliesPolicy.toList.map(value => "replies_policy" -> value.wire) ++
      exclusive.toList.map(value => "exclusive" -> value.toString)

  private def memberFields(accountIds: List[Id]): List[(String, String)] =
    accountIds.map(id => "account_ids[]" -> id.value)

private final class LiveLists[F[_]](runner: EndpointRunner[F]) extends Lists[F]:

  def list(): F[List[MastodonList]] =
    runner.run(Lists.listEndpoint)

  def get(id: Id): F[MastodonList] =
    runner.run(Lists.getEndpoint(id))

  def create(
      title: String,
      repliesPolicy: Option[RepliesPolicy],
      exclusive: Option[Boolean]
  ): F[MastodonList] =
    runner.run(Lists.createEndpoint(title, repliesPolicy, exclusive))

  def update(
      id: Id,
      title: String,
      repliesPolicy: Option[RepliesPolicy],
      exclusive: Option[Boolean]
  ): F[MastodonList] =
    runner.run(Lists.updateEndpoint(id, title, repliesPolicy, exclusive))

  def delete(id: Id): F[Unit] =
    runner.run(Lists.deleteEndpoint(id))

  def accounts(
      id: Id,
      maxId: Option[Id],
      sinceId: Option[Id],
      minId: Option[Id],
      limit: Option[Int]
  ): F[List[Account]] =
    runner.run(Lists.accountsEndpoint(id, maxId, sinceId, minId, limit))

  def addAccounts(id: Id, accountIds: List[Id]): F[Unit] =
    runner.run(Lists.addAccountsEndpoint(id, accountIds))

  def removeAccounts(id: Id, accountIds: List[Id]): F[Unit] =
    runner.run(Lists.removeAccountsEndpoint(id, accountIds))

  def mapK[G[_]: MonadThrow](transform: F ~> G): Lists[G] =
    new LiveLists[G](runner.mapK(transform))
