// Five-project build for the 0.4.0 module split.
//
// The dependency graph is the architecture: scaladon-core declares no third-party dependency;
// scaladon-circe owns every circe type; scaladon-cats owns the effect layer; scaladon-http4s is the
// only project that names http4s; the root project is both the build aggregator and the published
// compatibility aggregate. M-06 removes the transitional cats dependencies marked below.

ThisBuild / organization := "ca.schwitzer"
ThisBuild / version      := "0.4.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.10.1"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard"
)

ThisBuild / Test / parallelExecution := false

lazy val catsVersion            = "2.13.0"
lazy val catsEffectVersion      = "3.7.1"
lazy val circeVersion           = "0.14.17"
lazy val http4sVersion          = "0.23.39"
lazy val fs2Version             = "3.15.0"
lazy val munitVersion           = "1.3.6"
lazy val munitCatsEffectVersion = "2.2.1"
lazy val munitScalacheckVersion = "1.3.1"
lazy val scalacheckVersion      = "1.20.0"

// scaladon-core: values and errors only; no third-party compile dependency is allowed here.
lazy val core = project
  .in(file("core"))
  .settings(
    name := "scaladon-core",
    libraryDependencies ++= Seq(
      "org.scalameta"  %% "munit"            % munitVersion           % Test,
      "org.scalameta"  %% "munit-scalacheck" % munitScalacheckVersion % Test,
      "org.scalacheck" %% "scalacheck"       % scalacheckVersion      % Test
    ),
    BuildGuard.settings(
      Seq("cats-", "circe-", "http4s-", "fs2-", "log4cats-", "munit-cats-effect")
    )
  )

// scaladon-circe: the circe boundary; it holds the codecs and the circe-typed wire values.
lazy val circe = project
  .in(file("circe"))
  .dependsOn(core % "compile->compile")
  .settings(
    name := "scaladon-circe",
    libraryDependencies ++= Seq(
      "io.circe"       %% "circe-core"       % circeVersion,
      "io.circe"       %% "circe-parser"     % circeVersion,
      "org.typelevel"  %% "cats-core"        % catsVersion,
      "org.scalameta"  %% "munit"            % munitVersion           % Test,
      "org.scalameta"  %% "munit-scalacheck" % munitScalacheckVersion % Test,
      "org.scalacheck" %% "scalacheck"       % scalacheckVersion      % Test
    )
  )

// scaladon-cats: the effect layer. It declares no http4s, fs2 or log4cats dependency and no direct
// circe-parser entry; the remaining circe-parser jar reaches it transitively through scaladon-circe.
lazy val cats = project
  .in(file("cats"))
  .dependsOn(core % "compile->compile", circe % "compile->compile")
  .settings(
    name := "scaladon-cats",
    libraryDependencies ++= Seq(
      "org.typelevel"  %% "cats-core"          % catsVersion,
      "org.typelevel"  %% "cats-effect"        % catsEffectVersion,
      "io.circe"       %% "circe-core"         % circeVersion,
      "org.scalameta"  %% "munit"              % munitVersion           % Test,
      "org.typelevel"  %% "munit-cats-effect"  % munitCatsEffectVersion % Test,
      "org.scalameta"  %% "munit-scalacheck"   % munitScalacheckVersion % Test,
      "org.scalacheck" %% "scalacheck"         % scalacheckVersion      % Test
    ),
    BuildGuard.settings(Seq("http4s-", "fs2-", "log4cats-"))
  )

// scaladon-http4s: the only project allowed to name http4s; it depends on the Cats client.
lazy val http4s = project
  .in(file("http4s"))
  .dependsOn(cats % "compile->compile")
  .settings(
    name := "scaladon-http4s",
    libraryDependencies ++= Seq(
      "org.http4s"     %% "http4s-ember-client" % http4sVersion,
      "org.typelevel"  %% "cats-effect"         % catsEffectVersion,
      "co.fs2"         %% "fs2-io"              % fs2Version,
      "org.slf4j"       % "slf4j-nop"           % "2.0.20"               % Test,
      "org.scalameta"  %% "munit"               % munitVersion           % Test,
      "org.typelevel"  %% "munit-cats-effect"   % munitCatsEffectVersion % Test,
      "org.scalameta"  %% "munit-scalacheck"    % munitScalacheckVersion % Test,
      "org.scalacheck" %% "scalacheck"          % scalacheckVersion      % Test
    )
  )

// scaladon: the root aggregator and the published compatibility aggregate. No sources of its own.
lazy val root = project
  .in(file("."))
  .aggregate(core, circe, cats, http4s)
  .dependsOn(cats % "compile->compile", http4s % "compile->compile")
  .settings(name := "scaladon")
