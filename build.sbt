import NPM._

PlayKeys.playRunHooks += NPM(baseDirectory.value / "assets")

name := """Soxx"""
organization := "org.soxx"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

val theScalaVersion = "2.12.4"

scalaVersion := theScalaVersion
ensimeScalaVersion in ThisBuild := theScalaVersion

scalacOptions ++= Seq(
  "-feature",
  /*
  "-Ywarn-unused-import",
  "-Ywarn-unused",
  "-Ywarn-dead-code"
   */
)

libraryDependencies ++= Seq(
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test,
  ws,

  guice,

  "org.mongodb.scala" %% "mongo-scala-driver" % "2.2.1",
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.0"
)
