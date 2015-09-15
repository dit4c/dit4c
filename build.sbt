import com.typesafe.sbt.web._
import sbtrelease._
import ReleaseStateTransformations._

name := "dit4c"

// Overriding publishArtifactsAction, so unnecessary for actual operation
publishTo := Some(Resolver.file("file",  new File( "/tmp" )) )

scalaVersion in ThisBuild := "2.11.6"

// Target JDK 1.8
scalacOptions in ThisBuild += "-target:jvm-1.8"

javacOptions in ThisBuild ++= Seq("-source", "1.8",  "-target", "1.8")

// Project definitions (automatically aggregated)

lazy val gatehouse   = project in file("dit4c-gatehouse")

lazy val highcommand = (project in file("dit4c-highcommand")).
  enablePlugins(PlayScala, SbtWeb)

lazy val machineshop = project in file("dit4c-machineshop")

lazy val switchboard = project in file("dit4c-switchboard")

// Release settings

releaseSettings

crossScalaVersions := Nil

