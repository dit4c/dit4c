import SharedDependencyVersions._

name  := "dit4c-common"

crossScalaVersions := Nil

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  Seq(
    "com.typesafe.akka"   %%  "akka-http-core"  % akkaHttpV,
    "com.trueaccord.scalapb" %% "scalapb-runtime" % scalapbV % "protobuf",
    "org.bouncycastle"    %   "bcpkix-jdk15on"  % "1.55",
    "org.bouncycastle"    %   "bcpg-jdk15on"    % "1.55",
    "com.pauldijou"       %%  "jwt-play-json"   % "0.7.1",
    "com.softwaremill.macwire"  %%  "macros"    % macwireV % "provided",
    "com.softwaremill.macwire"  %%  "util"      % macwireV % "test",
    "com.typesafe.play"   %%  "play-json"       % playV % "provided",
    "com.typesafe.play"   %%  "play-json"       % playV % "test",
    "com.typesafe.akka"   %%  "akka-http-testkit" % akkaHttpV % "test",
    "org.specs2"          %%  "specs2-core"     % specs2V % "test",
    "org.specs2"          %%  "specs2-mock"     % specs2V % "test",
    "org.specs2"          %%  "specs2-scalacheck" % specs2V % "test",
    "dnsjava"             % "dnsjava"           % "2.1.7" % "test"
  )
}

fork := true

scalacOptions ++= Seq("-feature")

managedSourceDirectories in Compile += target.value / "protobuf-generated"

PB.targets in Compile := Seq(
  scalapb.gen(grpc = false) -> (target.value / "protobuf-generated")
)
