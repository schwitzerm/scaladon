# scaladon

A Mastodon API client for Scala, rewritten as a **pure, effect-polymorphic, typeclass-driven** library: [Cats](https://typelevel.org/cats/) for the abstractions, [Cats Effect](https://typelevel.org/cats-effect/) for the effect model, [circe](https://circe.github.io/circe/) for JSON, and a transport layer expressed as a typeclass so that no HTTP library leaks into the API.

```
Endpoint[A]  ──►  EndpointRunner[F]  ──►  HttpBackend[F]  ──►  network
   (data)          (interpreter)            (typeclass)
     │                                          ▲
     └── algebras: Statuses[F], …               └── IO interpreter, Future adapter, test stub
```

## Status: refactor in progress

| | |
|---|---|
| Version | `0.4.0-SNAPSHOT` (unreleased) |
| Branch | `dev/refactor` |
| Language | Scala 3.10.1 |
| Build | sbt 1.13.1 |
| Effects | Cats Effect 3.7.1, Cats 2.13.0 |
| HTTP | http4s 0.23.39 (ember client) |
| JSON | circe 0.14.17 |
| Tests | 35, `sbt test` |
| JDK | built and tested on JDK 27 |

The 0.3.2 library is **frozen in [`legacy/`](legacy/README.md)** and is not compiled by the build. Read it for endpoint paths, field names and the list of bugs the rewrite must not reproduce.

This is a skeleton, not a finished client: `Statuses` is the only ported namespace. The transport is wired: `http4s`/ember interprets the `HttpBackend` algebra, and a `Future`-based backend can be lifted into `IO` for reuse. See [Roadmap](#roadmap).

## Requirements

* A JDK that can run sbt 1.13 and Scala 3.10 — verified on JDK 27; JDK 8 is no longer needed.
* sbt 1.13.1 (pinned in `project/build.properties`).
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
| Transport | `.http` | `HttpMethod`, `Host`, `Request`, `Response`, `BearerToken`, `HttpBackend[F]` | circe `Json`, domain |
| API | `.api` | `Endpoint[A]`, `EndpointRunner[F]`, `Statuses[F]`, `LiveStatuses[F]` | transport, codec, domain |
| Translation | `.translation` | `Future ↔ IO` natural transformations and backend adapters | Cats Effect, transport |
| Interpreter | `.http4s` | `Http4sBackend` (ember), `MastodonHttp4s.resource` | http4s, Cats Effect, transport |
| Client | `.Mastodon` | `Mastodon[F[_]]`, an aggregate of the algebras | API, transport |

Design rules the skeleton already enforces:

* **Effects only at the edge.** `domain`, `codec` and endpoint construction are total functions. The only effectful member is `HttpBackend.run`.
* **One error channel.** API methods return `F[A]`; every expected failure is raised inside `F` as `ApiException(ApiError)`. The payload is a pure ADT — `Transport`, `Http`, `RateLimited`, `Decode`, `NotAuthenticated` — with `retryable`, `isNotFound` and `isUnauthorized`. `.attemptApi` recovers typed failures as values when you want them. The legacy `MastodonResponse` ADT (errors as a `Throwable` wrapper you had to unwrap) is gone.
* **Typeclasses, not inheritance.** JSON capability is `Decoder[A]`; transport capability is `HttpBackend[F]`; the runner needs only `Applicative[F]`.
* **Endpoints are data.** An `Endpoint[A]` is a method, path, query, body, an auth flag and a decoder. It can be inspected, printed, or run against a stub — the legacy client built and fired requests inside each method.
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

Lifting an existing `Future`-based transport (the shape of the legacy client):

```scala
import ca.schwitzer.scaladon.http.HttpBackend
import ca.schwitzer.scaladon.translation.FutureTranslation
import scala.concurrent.Future

// `legacySend` is the old Akka flow adapted to `Future[Response]`.
val legacy: HttpBackend[Future] = HttpBackend.of[Future](request => legacySend(request))
val modern: HttpBackend[IO]    = FutureTranslation.futureBackendToIO(legacy)
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
client.statuses.post("hi").attemptApi: IO[Either[ApiError, Status]]
```

## What is ported

| Area | Legacy (`legacy/`) | New |
|---|---|---|
| Ids | `Int` | `Id`, an opaque `String` |
| Timestamps | Joda `DateTime`, offset pattern | `java.time.Instant`, RFC 3339 |
| Visibility | `StatusVisibility` + partial `Reads` | Scala 3 `enum Visibility` + total `fromWire` |
| Errors | `MastodonResponse` / `MastodonError(Throwable)` | `ApiException(ApiError)` on the effect channel; `attemptApi` for values |
| Transport | `Flow` + `Future[HttpResponse]` | `HttpBackend[F]` + `mapK` |
| HTTP client | Akka HTTP `outgoingConnectionHttps` | http4s 0.23 ember, as `Http4sBackend` |
| Endpoints | methods on nested objects | `Endpoint[A]` values + runner |
| `Statuses` | all 11 methods, several broken | `fetch`, `post`, `delete`; remaining methods to port |
| `Accounts`, `Timelines`, `Search`, … | 11 namespaces | not yet ported |
| `createApp`, `login`, disk cache | implemented | **not ported** (open decision) |
| Tests | none | 35 |

## Repository layout

```
.
├── build.sbt                       # Scala 3.10.1, cats-effect 3.7, circe, munit
├── project/build.properties        # sbt 1.13.1
├── README.md                       # this file (also the GitHub Pages site)
├── AGENTS.md                       # conventions, refactor contract, decisions
├── src/main/scala/ca/schwitzer/scaladon/
│   ├── Mastodon.scala              # Mastodon[F[_]] aggregate + live/anonymous constructors
│   ├── domain/                     # pure data + ApiError/ApiException
│   ├── codec/Codecs.scala          # circe instances
│   ├── http/                       # transport typeclass and request/response values
│   ├── api/                        # Endpoint, EndpointRunner, Statuses, attemptApi
│   ├── http4s/                     # ember interpreter + MastodonHttp4s.resource
│   └── translation/                # Future <-> IO
├── src/test/scala/ca/schwitzer/scaladon/   # 5 suites, 35 tests
└── legacy/                         # frozen 0.3.2: sources, publishing config, CI, defect ledger
```

The build never compiles `legacy/`: sbt reads only `src/main/scala` and `src/test/scala`.

## Roadmap

1. Port the remaining `Statuses` methods (`fetchContext`, `fetchCard`, `favourite`, `unfavourite`, `reblog`, `unreblog`, `favouritedBy`, `rebloggedBy`) with tests against recorded payloads.
2. Port the remaining namespaces one algebra at a time: `Accounts`, `Timelines`, `Notifications`, `Search`, then the rest.
3. Decide the fate of `createApp`/`login`: caller-supplied tokens, an OAuth algebra, or both.
4. Add law tests (codec round-trips, `mapK` identity/composition) and property tests over recorded fixtures.
5. Re-establish publishing (Central Portal) and CI; delete the frozen publishing config once replaced.

## License and lineage

WTFPL — see [LICENSE](LICENSE). Author: Mitchell Schwitzer. The published `0.3.2` artifact is unmaintained; a community fork lives at <https://github.com/Sciss/scaladon>.
