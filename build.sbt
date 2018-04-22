import NPM._

PlayKeys.playRunHooks += NPM(baseDirectory.value / "app" / "assets")

name := """Soxx"""
organization := "org.soxx"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

val theScalaVersion = "2.11.11"

scalaVersion := theScalaVersion
ensimeScalaVersion in ThisBuild := theScalaVersion

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test

libraryDependencies += ws
libraryDependencies += "org.mongodb.scala" %% "mongo-scala-driver" % "2.2.1"

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "org.soxx.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "org.soxx.binders._"
