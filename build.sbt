// New implementation. The 0.3.2 sources and their publishing/CI configuration were removed
// from this branch; only the current Scala 3 tree is compiled.

ThisBuild / organization := "ca.schwitzer"
ThisBuild / version      := "0.4.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.10.1"

lazy val catsVersion            = "2.13.0"
lazy val catsEffectVersion      = "3.7.1"
lazy val circeVersion           = "0.14.17"
lazy val http4sVersion          = "0.23.39"
lazy val munitVersion           = "1.3.6"
lazy val munitCatsEffectVersion = "2.2.1"
lazy val munitScalacheckVersion = "1.3.1"
lazy val scalacheckVersion      = "1.20.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "scaladon",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Wvalue-discard"
    ),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"   % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "io.circe"      %% "circe-core"  % circeVersion,
      "io.circe"      %% "circe-parser" % circeVersion,
      "org.http4s"    %% "http4s-ember-client" % http4sVersion,
      "org.slf4j"     %  "slf4j-nop"          % "2.0.20"               % Test,
      "org.scalameta" %% "munit"               % munitVersion           % Test,
      "org.typelevel" %% "munit-cats-effect"   % munitCatsEffectVersion % Test,
      "org.scalameta" %% "munit-scalacheck"    % munitScalacheckVersion % Test,
      "org.scalacheck" %% "scalacheck"         % scalacheckVersion      % Test
    ),
    Test / parallelExecution := false
  )
