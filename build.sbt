import NPM._

PlayKeys.playRunHooks += NPM(baseDirectory.value / "assets")

name := """Soxx"""
organization := "org.soxx"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

val theScalaVersion = "2.12.4"

scalaVersion := theScalaVersion
ensimeScalaVersion in ThisBuild := theScalaVersion

libraryDependencies ++= Seq(
  guice,
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test,

  "org.mongodb.scala" %% "mongo-scala-driver" % "2.2.1",
  ws,
  "com.corundumstudio.socketio" % "netty-socketio" % "1.7.14",
)
