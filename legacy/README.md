# Legacy sources — scaladon 0.3.2 (frozen reference)

This directory is a **read-only snapshot of the pre-refactor library**. Nothing here is compiled by the build: sbt only compiles `src/main/scala` and `src/test/scala` at the repository root, so `legacy/` is inert unless someone deliberately adds it as a source directory.

It is kept because the new implementation is a port, not a rewrite from a blank page: endpoint paths, parameter names, request shapes and the defect list below are the specification of what the old client actually did. Read it, quote it in issues, and copy paths or field names from it — do not edit it.

```
legacy/
├── .travis.yml                     # historical CI (Scala 2.11/2.12 on oraclejdk8)
├── publishing/
│   ├── plugins.sbt                 # sbt-sonatype 1.1, sbt-pgp 1.0.0 (0.13-era)
│   └── sonatype.sbt                # OSSRH publishing metadata, `in Global` syntax
└── src/main/scala/ca/schwitzer/scaladon/
    ├── Mastodon.scala              # class + 12 namespaces + createApp/login/storage
    ├── MastodonResponse.scala      # response/error ADT
    ├── package.scala               # dateReads, unitReads, handleAs, entity helpers
    ├── AccessToken.scala           # akka HttpCredentials wrapper
    ├── AppCredentials.scala        # app registration data + JSON
    ├── AGENTS.md                   # notes for this tree: pipeline, namespace map, storage
    └── models/                     # 14 case classes + play-json Reads
        ├── README.md               # model catalogue
        └── AGENTS.md               # model conventions
```

## How it was built

```bash
sbt compile        # sbt 0.13.15, Scala 2.11.8/2.12.2, JDK 8
sbt test           # no test sources existed
```

It does not build on JDK 17+: sbt 0.13.15 installs a `SecurityManager`. The last verified attempt on JDK 27 failed with `java.lang.UnsupportedOperationException: Setting a Security Manager is not supported`. The maintainer has confirmed the snapshot compiles on a JDK 8 system, so treat any local failure on a newer JDK as a toolchain gap, not a defect. The new build does not depend on this tree at all.

## Mapping to the new layers

| Legacy construct | Where it goes |
|---|---|
| `Mastodon` class, nested `object` namespaces | `api/` algebras (`Statuses[F[_]]`, …) implemented over endpoint values |
| `makeRequest` / `makeAuthorizedRequest`, `flow` | `http/HttpBackend[F[_]]` + `EndpointRunner` |
| `AccessToken` (`akka` `HttpCredentials`) | `http/BearerToken` |
| `handleAs` + `MastodonResponse` / `MastodonError` | `EndpointRunner` + `domain/ApiError`, raised as `ApiException` in `F` (`attemptApi` for values) |
| `models/*.scala` (`Int` ids, Joda `DateTime`, play-json) | `domain/` (`Id` as opaque `String`, `java.time.Instant`) + circe codecs |
| `toot`, `Statuses.post` | `Statuses[F].post` (media and spoiler handling still to be ported) |
| `createApp`, `login`, `~/.scaladon` cache | not ported yet; the on-disk cache is an open decision |
| `sbt 0.13` / `sonatype` / `travis` config | `legacy/publishing/`, `legacy/.travis.yml`; release automation is to be rebuilt |

## Defect ledger

Known wrong behaviour in the snapshot. Line numbers are relative to the files in this directory and may drift; search the symbol instead. These are the behaviours the new implementation must *not* reproduce, and each deserves a test in the new tree before the port of that endpoint is called complete.

| # | Where | Defect | Impact |
|---|---|---|---|
| 1 | `Mastodon.scala:137,160,190,278,296,323,350,398,471,509,581,642,664` | GET endpoints put `limit`, `max_id`, `since_id`, `only_media`, `local`, `q`, `resolve` in a JSON **request body** (`entity = entity`) | Mastodon ignores them; pagination, search and local-only filters silently do nothing |
| 2 | `Mastodon.scala:733,745` | `favourite`/`unfavourite` call `/api/v1/status/{id}/...` (singular) | 404 from the server |
| 3 | `Mastodon.scala:685` | `println(entity)` left in `Statuses.post` | Debug output on every toot |
| 4 | `models/*.scala` | All ids are `Int` | Mastodon ids are decimal strings and exceed `Int.MaxValue`; decoding fails or truncates |
| 5 | `package.scala:14` | `dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"` uses Joda's unquoted `Z` (numeric offset), while Mastodon emits RFC 3339 `...Z` | `created_at` parsing fails on real payloads |
| 6 | `Mastodon.scala:858-864` | `createApp` computes `Future.failed(...)` when `mkdirs` fails but discards it and continues; `saveAppData` never creates parents | Failure surfaces later as an unrelated `FileNotFoundException` |
| 7 | `Mastodon.scala:22` | One long-lived `outgoingConnectionHttps` flow with `runWith(Sink.head)` per request | No pooling/concurrency control/retry |
| 8 | `package.scala:30-45` | `handleAs` matches only `isSuccess`/`isFailure` | `MatchError` on 1xx/3xx/interim statuses |
| 9 | `Attachment.scala:33`, `Notification.scala:35`, `Status.scala:71` | Enum `Reads` use a partial `map` | A new server-side enum value crashes decoding instead of yielding a typed error |
| 10 | `Mastodon.scala:676-686` | `spoiler_text` uses `asInstanceOf[Option[String]]`; absent options are emitted as JSON `null` | Fragile and inconsistent with endpoints that omit empty fields |
| 11 | `.gitignore` (at the time) | Bare `test` pattern | `src/test` would be silently untracked; removed in the refactor |
| 12 | `publishing/*.sbt` | play-json `2.6.0-M6` milestone; sbt-sonatype 1.1 / sbt-pgp 1.0.0; OSSRH-era publishing | Publishing path is likely broken (OSSRH sunset); release tooling to be rebuilt |
| 13 | `Mastodon.scala:831-849` | Credentials persisted as plaintext JSON in `~/.scaladon`; token never saved | Any process can read app secrets; callers must retain tokens themselves |

Also true of the snapshot and relevant to the port:

* No media upload, no profile update (`AccountUpdateData` is unused), no streaming API, no authorization-code flow.
* `AccountUpdateData` is dead code.
* `Mastodon`'s constructor is private; the only construction path is `createApp`, which may perform network I/O.
* There are no tests.

## Why keep it in the repository rather than in git history

Because the port is incremental and cross-referencing is constant: the new endpoint values, field codecs and path constants are written by reading the old methods. Deleting the tree would turn every one of those lookups into a `git show master:` invocation. `legacy/` also keeps the only written record of the 2017 API surface and its bugs.
