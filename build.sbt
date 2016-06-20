import com.typesafe.sbt.web._
import sbtrelease._
import ReleaseStateTransformations._

name := "dit4c"

// Overriding publishArtifactsAction, so unnecessary for actual operation
publishTo := Some(Resolver.file("file",  new File( "/tmp" )) )

scalaVersion in ThisBuild := "2.11.8"

// Target JDK 1.8
scalacOptions in ThisBuild += "-target:jvm-1.8"

javacOptions in ThisBuild ++= Seq("-source", "1.8",  "-target", "1.8")

// Attempt to fix cryptic Travis CI sbt.ForkMain error
javaOptions in ThisBuild += "-Xmx1G"

// Stop sub-projects from running their tests interwoven
concurrentRestrictions in Global := Seq(
  Tags.exclusive(Tags.Test),
  Tags.limit(Tags.Test, 1)
)

// Project definitions (automatically aggregated)
lazy val common      = project in file("dit4c-common")

lazy val gatehouse   = (project in file("dit4c-gatehouse")).dependsOn(common)

lazy val highcommand = (project in file("dit4c-highcommand")).
  enablePlugins(PlayScala, SbtWeb)

lazy val machineshop = (project in file("dit4c-machineshop")).dependsOn(common)

lazy val portal = (project in file("dit4c-portal")).enablePlugins(PlayScala, SbtWeb)

lazy val scheduler = (project in file("dit4c-scheduler"))

lazy val switchboard = (project in file("dit4c-switchboard")).dependsOn(common)

// Release settings

releaseSettings

crossScalaVersions := Nil

buildOptions in docker in ThisBuild := BuildOptions(
  pullBaseImage = BuildOptions.Pull.Always
)
