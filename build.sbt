scalaVersion := "2.12.2"
crossScalaVersions := Seq("2.11.8", scalaVersion.value)

name := "scaladon"
organization := "ca.schwitzer"
version := "0.4.0-SNAPSHOT"

libraryDependencies ++= Seq(
  //http & streams
  "com.typesafe.akka" %% "akka-http" % "10.0.5",
  "com.typesafe.akka" %% "akka-http-core" % "10.0.5",
  "com.typesafe.akka" %% "akka-stream" % "2.5.0",

  //json
  "com.typesafe.play" %% "play-json" % "2.6.0-M6",

  //logging
  "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
  "ch.qos.logback" % "logback-classic" % "1.1.7",

  //testing
  "org.scalactic" %% "scalactic" % "3.0.1",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)

useGpg := true
