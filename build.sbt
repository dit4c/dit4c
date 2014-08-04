import sbtrelease._
import ReleaseStateTransformations._

name := "dit4c"

scalaVersion in ThisBuild := "2.11.2"

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

ReleaseKeys.releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,              // : ReleaseStep
  inquireVersions,                        // : ReleaseStep
  runClean,                               // : ReleaseStep
  runTest,                                // : ReleaseStep
  setReleaseVersion,                      // : ReleaseStep
  commitReleaseVersion,                   // : ReleaseStep, performs the initial git checks
  tagRelease,                             // : ReleaseStep
  setNextVersion,                         // : ReleaseStep
  commitNextVersion,                      // : ReleaseStep
  pushChanges                             // : ReleaseStep, also checks that an upstream branch is properly configured
)