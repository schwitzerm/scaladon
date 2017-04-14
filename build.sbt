scalaVersion := "2.12.1"

name := "scaladon"
organization := "ca.schwitzer"
version := "0.1.4"

libraryDependencies ++= Seq(
  //http & streams
  "com.typesafe.akka" %% "akka-http" % "10.0.5",
  "com.typesafe.akka" %% "akka-http-core" % "10.0.5",
  "com.typesafe.akka" %% "akka-stream" % "2.4.17",

  //json
  "com.typesafe.play" %% "play-json" % "2.6.0-M6",

  //testing
  "org.scalactic" %% "scalactic" % "3.0.1",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)
