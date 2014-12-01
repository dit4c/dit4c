import sbtrelease._
import ReleaseStateTransformations._

name := "dit4c"

// Overriding publishArtifactsAction, so unnecessary for actual operation
publishTo := Some(Resolver.file("file",  new File( "/tmp" )) )

scalaVersion in ThisBuild := "2.11.4"

// Target JDK 1.7
scalacOptions in ThisBuild += "-target:jvm-1.7"

// Project definitions (automatically aggregated)

lazy val gatehouse   = project in file("dit4c-gatehouse")

lazy val highcommand = (project in file("dit4c-highcommand")).
  enablePlugins(PlayScala).
  enablePlugins(SbtWeb)

lazy val machineshop = project in file("dit4c-machineshop")

// Release settings

releaseSettings

crossScalaVersions := Nil
