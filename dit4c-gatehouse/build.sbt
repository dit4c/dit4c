import sbtdocker.{ImageName, Dockerfile}
import DockerKeys._

name := "dit4c-gatehouse"

crossScalaVersions := Nil

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

fork in run := true

libraryDependencies ++= {
  val akkaV = "2.3.5"
  val akkaHttpV = "2.0-M2"
  Seq(
    "com.typesafe.akka"   %%  "akka-http-experimental" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-spray-json-experimental" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-xml-experimental" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-testkit-experimental" % akkaHttpV % "test",
    "io.spray"            %%  "spray-json"      % "1.3.1",
    "com.typesafe.akka"   %%  "akka-actor"      % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"    % akkaV   % "test",
    "org.specs2"          %%  "specs2-core"     % "3.6.4" % "test",
    "com.nimbusds"        %   "nimbus-jose-jwt" % "2.22.1",
    "com.github.scopt"    %%  "scopt"           % "3.2.0",
    "com.google.code.findbugs" % "jsr305"       % "3.0.1",
    "com.github.docker-java" % "docker-java"    % "3.0.0-SNAPSHOT"
  )
}

resolvers ++= Seq(
  "Sonatype snapshots" at "https://oss.sonatype.org/content/groups/staging/")

Revolver.settings

packSettings

packMain := Map("dit4c-gatehouse" -> "dit4c.gatehouse.Boot")

sbtdocker.Plugin.dockerSettings

// Make docker depend on the package task, which generates a jar file of the application code
docker <<= docker.dependsOn(pack)

// Docker build
dockerfile in docker := {
  import sbtdocker.Instructions._
  import sbtdocker._
  val appDir = (packTargetDir / "pack").value
  immutable.Dockerfile.empty
    .from("dit4c/dit4c-platform-basejre")
    .add(appDir, "/opt/dit4c-gatehouse")
    .run("chmod", "+x", "/opt/dit4c-gatehouse/bin/dit4c-gatehouse")
    .cmd("sh", "-c", """
      set -e
      JAVA_OPTS="-Dsun.net.inetaddr.ttl=60"
      cd /opt
      exec /opt/dit4c-gatehouse/bin/dit4c-gatehouse -i 0.0.0.0 -s $PORTAL_URL/public-keys
      """)
    .expose(8080)
}

// Set a custom image name
imageName in docker := {
  ImageName(namespace = Some("dit4c"),
    repository = "dit4c-platform-gatehouse",
    tag = Some(version.value))
}

ReleaseKeys.publishArtifactsAction := docker.value
