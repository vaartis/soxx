import NPM._

PlayKeys.playRunHooks += NPM(baseDirectory.value / "assets")

name := """Soxx"""
organization := "org.soxx"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

val theScalaVersion = "2.12.4"

scalaVersion := theScalaVersion

fork in Test := false // Disable forking for tests, so we can pass system properties easily

scalacOptions ++= Seq(
  "-feature",
  /*
  "-Ywarn-unused-import",
  "-Ywarn-unused",
  "-Ywarn-dead-code"
   */
)

libraryDependencies ++= Seq(
  guice,
  ws,

  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test,
  "de.leanovate.play-mockws" %% "play-mockws" % "2.6.2" % Test,
  "com.typesafe.akka" %% "akka-testkit" % "2.5.13" % Test,
  "com.jsuereth" %% "scala-arm" % "2.0",

  "org.mongodb.scala" %% "mongo-scala-driver" % "2.4.0",
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.0",

  "io.minio" % "minio" % "4.0.2",

  "tech.sparse" %%  "toml-scala" % "0.1.2-SNAPSHOT"
)
