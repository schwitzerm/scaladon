# scaladon

A Mastodon API client for Scala, rewritten as a **pure, effect-polymorphic, typeclass-driven** library: [Cats](https://typelevel.org/cats/) for the abstractions, [Cats Effect](https://typelevel.org/cats-effect/) for the effect model, [circe](https://circe.github.io/circe/) for JSON, and a transport layer expressed as a typeclass so that no HTTP library leaks into the API.

```
Endpoint[A]  ──►  EndpointRunner[F]  ──►  HttpBackend[F]  ──►  network
   (data)          (interpreter)            (typeclass)
     │                                          ▲
     └── algebras: Statuses[F], …               └── IO interpreter, Future adapter, test stub
```

## Status: 0.4.0 in progress

| | |
|---|---|
| Version | `0.4.0-SNAPSHOT` (unreleased) |
| API | Mastodon 5.0 / API version 12 (latest only) |
| Language | Scala 3.10.1 |
| Build | sbt 2.1.1 |
| Effects | Cats Effect 3.7.1, Cats 2.13.0 |
| HTTP | http4s 0.23.39 (ember client) |
| JSON | circe 0.14.17 |
| Tests | 419, `sbt test` |
| JDK | built and tested on JDK 27.0.1 |

The 0.3.2 implementation, its publishing configuration and its CI files have been removed from this branch. Only the current Scala 3 implementation is built and tested.

The adapter targets **Mastodon API version 12** (Mastodon 5.0) and deliberately drops compatibility with older servers and with the 0.3.2 shape. `Instances`, `Statuses`, `Accounts`, `Profile`, `Timelines`, `Collections`, `Notifications` and `OAuth` are ported, together with the full closeout namespace set below; only the deliberately deferred areas are listed in the [Roadmap](#roadmap). The transport is wired: `http4s`/ember interprets the `HttpBackend` algebra, and a `Future`-based backend can be lifted into `IO` for reuse.

## Requirements

* A JDK that can run sbt 2.1.1 and Scala 3.10 — verified on JDK 27.0.1; JDK 8 is no longer needed.
* sbt 2.1.1 (pinned in `project/build.properties`).
* No local services and no credentials: tests stub the network (one test builds the ember client without connecting).

## Build and test

```bash
sbt compile
sbt test
```

No formatter is configured yet; the code follows the conventions in [AGENTS.md](AGENTS.md#conventions).

## Architecture

Layers, one direction of dependency: outer layers may know inner ones, never the reverse.

| Layer | Package | Contains | Depends on |
|---|---|---|---|
| Domain | `ca.schwitzer.scaladon.domain` | `Id`, `Visibility`, `Account`, `Status`, `HttpStatus`, `ApiError`, `ApiException` | Cats only |
| Codec | `.codec` | circe `Decoder`/`Encoder` instances for domain types | circe, domain |
| Transport | `.http` | `HttpMethod`, `Host`, `Request` (redacted logging), `Response`, `BearerToken`, `HttpBackend[F]` | circe `Json`, domain |
| API | `.api` | `Endpoint[A]`, `EndpointRunner[F]`, `Statuses[F]`, `LiveStatuses[F]` | transport, codec, domain |
| Translation | `.translation` | `Future ↔ IO` natural transformations and backend adapters | Cats Effect, transport |
| Interpreter | `.http4s` | `Http4sBackend` (ember), `MastodonHttp4s.resource` | http4s, Cats Effect, transport |
| Client | `.Mastodon` | `Mastodon[F[_]]`, an aggregate of the algebras | API, transport |

Design rules the adapter enforces:

* **Effects only at the edge.** `domain`, `codec` and endpoint construction are total functions. The only effectful member is `HttpBackend.run`.
* **One error channel.** API methods return `F[A]`; every expected failure is raised inside `F` as `ApiException(ApiError)`. The payload is a pure ADT — `Transport`, `Http`, `RateLimited`, `Decode`, `NotAuthenticated` — with `retryable`, `isNotFound` and `isUnauthorized`. `.attemptApi` recovers typed failures as values when you want them.
* **Safe request logging.** `Request.toString` redacts `Authorization`/`Proxy-Authorization` and every credential-bearing form field the client sends (`client_secret`, `code`, `refresh_token`, `access_token`, `password`, `token`, `code_verifier`, and nested `…[auth]` keys such as `subscription[keys][auth]`), and renders body/multipart as presence markers; the wire headers still carry the bearer token. Multipart bodies get a fresh boundary per request, and part content types containing CR/LF/NUL are rejected before the client runs.
* **Typeclasses, not inheritance.** JSON capability is `Decoder[A]`; transport capability is `HttpBackend[F]`; the runner needs only `MonadThrow[F]`.
* **Endpoints are data.** An `Endpoint[A]` is a method, path, query, body, an auth flag and a decoder. It can be inspected, printed, or run against a stub.
* **Translation by natural transformation.** `HttpBackend[F].mapK` and `Mastodon[F].mapK` are `F ~> G`; `FutureTranslation` lifts a `Future`-based backend into `IO` and back with a `Dispatcher`. That is the bridge for reusing old code without rewriting its effects.
* **`Async` where the library needs it.** The http4s interpreter requires `Async[F]` (and `Network[F]` from fs2 for ember's sockets). The pure layers stay `Applicative`-minimal so `mapK` can still target effects without an `Async` instance — notably `Future`, which has none.

## Using it

The bundled transport is an http4s ember client. A token is all you need:

```scala
import cats.effect.{IO, IOApp}
import ca.schwitzer.scaladon.api.attemptApi
import ca.schwitzer.scaladon.domain.{ApiException, Id}
import ca.schwitzer.scaladon.http.{BearerToken, Host}
import ca.schwitzer.scaladon.http4s.MastodonHttp4s

object Demo extends IOApp.Simple:
  def run: IO[Unit] =
    MastodonHttp4s
      .resource[IO](Host("code.party"), BearerToken(sys.env("MASTODON_TOKEN")))
      .use { client =>
        client.statuses.fetch(Id("42")).attemptApi.flatMap {
          case Right(status)                   => IO.println(status.content)
          case Left(error) if error.isNotFound => IO.println("no such status")
          case Left(error) if error.retryable  => IO.println(s"retry later: ${error.message}")
          case Left(error)                     => IO.raiseError(ApiException(error))
        }
      }
```

`MastodonHttp4s.resource` owns the ember connection pool and closes it when the `Resource` is
released. If you already have a client, wrap it instead with `Http4sBackend.fromClient(client)`.
To test without a network, pass a stub backend:

```scala
import cats.effect.IO
import ca.schwitzer.scaladon.Mastodon
import ca.schwitzer.scaladon.domain.HttpStatus
import ca.schwitzer.scaladon.http.{BearerToken, Host, HttpBackend, Response}

val stub: HttpBackend[IO] =
  HttpBackend.of[IO](_ => IO.pure(Response(HttpStatus.NotFound, """{"error":"Record not found"}""")))
val client = Mastodon.live(stub, Host("code.party"), BearerToken("token"))
```

Lifting an existing `Future`-based transport:

```scala
import ca.schwitzer.scaladon.http.HttpBackend
import ca.schwitzer.scaladon.translation.FutureTranslation
import scala.concurrent.Future

// `existingSend` adapts any `Future[Response]` transport.
val existing: HttpBackend[Future] = HttpBackend.of[Future](request => existingSend(request))
val modern: HttpBackend[IO]       = FutureTranslation.futureBackendToIO(existing)
```

## Errors

Every expected failure travels on the effect's error channel as `ApiException(ApiError)`. Raising
means `timeout`, `retry`, `handleErrorWith` and `guarantee` compose with a call directly; the ADT
inside the exception keeps the failure inspectable:

```scala
enum ApiError:
  case Transport(cause: Throwable)                                        // no HTTP response
  case Http(status: HttpStatus, detail: Option[String], body: String)     // any other non-2xx
  case RateLimited(retryAfter: Option[FiniteDuration], detail: Option[String]) // 429
  case Decode(detail: String)                                             // 2xx, wrong JSON
  case NotAuthenticated(path: String)                                     // no token configured

  def retryable: Boolean
  def isNotFound: Boolean
  def isUnauthorized: Boolean
```

`detail` is Mastodon's own `{"error": …}` text (parsed, not the raw body), `body` is the raw body,
and `retryAfter` comes from the `Retry-After` header — which is why `http.Response` keeps headers.
Cancellation is never converted into an API error; it passes through untouched.

When you prefer values, use `attemptApi`:

```scala
client.statuses.post("hi").attemptApi: IO[Either[ApiError, StatusPostResult]]
```

## What is implemented

| Area | Current implementation |
|---|---|
| Ids | `Id`, an opaque `String` |
| Timestamps | `java.time.Instant`, RFC 3339 |
| Visibility | Scala 3 `enum Visibility` with a total `fromWire` |
| Errors | `ApiException(ApiError)` on the effect channel; `attemptApi` for values |
| Transport | `HttpBackend[F]` + `mapK` |
| HTTP client | http4s 0.23 ember, as `Http4sBackend` |
| Endpoints | `Endpoint[A]` values + runner; one shared `Endpoint.putForm` for form `PUT`s |
| Request logging | `Request.toString` redacts bearer/Proxy headers and every credential-bearing OAuth/Push form field (revoke `token`, PKCE `code_verifier`, nested `subscription[keys][auth]`); body/multipart are presence markers |
| Status media | `Status.mediaAttachments` decodes `media_attachments`; delete-and-redraft keeps the attachments |
| Multipart | a fresh boundary per request; part content types reject CR, LF and NUL |
| `Instances` | `GET /api/v2/instance`: API-version gate, server limits, VAPID key, timeline access |
| `Statuses` | `fetch`, `post` (quotes, scheduling, polls, media), `delete` (delete-and-redraft with media), `edit`, `context` (+ `Mastodon-Async-Refresh`), `card`, `favourite`/`unfavourite`, `reblog`/`unreblog`, `favouritedBy`/`rebloggedBy` |
| `Accounts` | `fetch`, `verifyCredentials`, `statuses`, `followers`, `following`, follow/block/mute actions, `relationships` |
| `Profile` | `GET`/`PATCH /api/v1/profile` (raw + formatted fields) |
| `Timelines` | `home` (5.0 exclude filters), `publicTimeline`, `tag`, `list` |
| `Collections` | full 4.6+ CRUD, items, `forAccount`, `inCollections` |
| `Notifications` | list (types, `supported_types`), get, clear, `NotificationFallback` |
| `OAuth` | discovery, token, revoke, userinfo, authorize URL, PKCE S256, `prompt=create` |
| `Media` | multipart upload (`POST /api/v2/media`), get/update/delete; 202-on-processing |
| `Polls` | `Status.poll` decoding, get, vote with repeated `choices[]` |
| `ScheduledStatuses` | list/get/reschedule/cancel scheduled posts |
| `AsyncRefreshes` | experimental `v1_alpha` refresh polling for `Mastodon-Async-Refresh` |
| `Search`, `Favourites` | `GET /api/v2/search`, favourites with cursors |
| `Blocks`, `Mutes`, `FollowRequests` | ported with pagination and authorize/reject |
| `Reports`, `Lists` | report filing and list CRUD/accounts |
| `Filters`, `Markers` | v2 filters with keywords/statuses, read markers |
| `Announcements`, `Trends` | announcements with reactions, trending tags/statuses/links |
| `Suggestions`, `Push` | v2 suggestions, Web Push subscription CRUD |
| Admin API, Streaming, Endorsements/Featured tags, Preferences, Annual reports, Directory, Tags | not yet ported |
| `createApp`, disk cache | not implemented (open decision); the OAuth token flow is |
| Tests | 404 |

## Repository layout

```
.
├── build.sbt                       # Scala 3.10.1, cats-effect 3.7, circe, munit
├── project/build.properties        # sbt 2.1.1
├── README.md                       # this file (also the GitHub Pages site)
├── AGENTS.md                       # conventions, refactor contract, decisions
├── src/main/scala/ca/schwitzer/scaladon/
│   ├── Mastodon.scala              # Mastodon[F[_]] aggregate + live/anonymous constructors
│   ├── domain/                     # pure data + ApiError/ApiException
│   ├── codec/Codecs.scala          # circe instances
│   ├── http/                       # transport typeclass and request/response values
│   ├── api/                        # Endpoint, Runner, and one algebra per namespace (statuses, media,
│   │                               # accounts, timelines, collections, notifications, oauth, search, …)
│   ├── http4s/                     # ember interpreter + MastodonHttp4s.resource
│   └── translation/                # Future <-> IO
└── src/test/scala/ca/schwitzer/scaladon/   # 44 suites, 419 tests
```

The build reads only `src/main/scala` and `src/test/scala`.

## Roadmap

1. Port the remaining namespaces: Admin API, Streaming, endorsements/featured tags, preferences, annual reports, directory and tags.
2. Decide the fate of `createApp` and the disk cache: caller-supplied tokens, dynamic registration, or neither.
3. Replace the remaining hand-written recorded payloads with fixtures captured from a live v12 server, and add per-algebra `mapK` law properties.
4. Re-establish publishing (Central Portal) and CI.

## License and lineage

WTFPL — see [LICENSE](LICENSE). Author: Mitchell Schwitzer. The published `0.3.2` artifact is unmaintained; a community fork lives at <https://github.com/Sciss/scaladon>.
