import SharedDependencyVersions._

name  := "dit4c-common"

crossScalaVersions := Nil

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  Seq(
    "com.typesafe.akka"   %%  "akka-http-core"  % akkaV,
    "com.trueaccord.scalapb" %% "scalapb-runtime" % "0.5.43" % "protobuf",
    "com.typesafe.akka"   %%  "akka-http-testkit" % akkaV % "test",
    "org.specs2"          %%  "specs2-core"     % specs2V % "test",
    "org.specs2"          %%  "specs2-mock"     % specs2V % "test",
    "dnsjava"             % "dnsjava"           % "2.1.7" % "test"
  )
}

fork := true

scalacOptions ++= Seq("-feature")

PB.targets in Compile := Seq(
  scalapb.gen(grpc = false) -> (sourceManaged in Compile).value
)
