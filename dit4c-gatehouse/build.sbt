import sbtdocker.{ImageName, Dockerfile}
import DockerKeys._

name := "dit4c-gatehouse"

crossScalaVersions := Nil

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

fork in run := true

libraryDependencies ++= {
  val akkaV = "2.3.5"
  val akkaHttpV = "1.0"
  Seq(
    "com.typesafe.akka"   %%  "akka-http-experimental" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-spray-json-experimental" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-xml-experimental" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-testkit-experimental" % akkaHttpV % "test",
    "io.spray"            %%  "spray-json"      % "1.3.1",
    "com.typesafe.akka"   %%  "akka-actor"      % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"    % akkaV   % "test",
    "org.specs2"          %%  "specs2-core"     % "2.4.2" % "test",
    "com.nimbusds"        %   "nimbus-jose-jwt" % "2.22.1",
    "com.github.scopt"    %%  "scopt"           % "3.2.0",
    "com.spotify"         %   "docker-client"   % "2.7.27"
  )
}

Revolver.settings

seq(com.github.retronym.SbtOneJar.oneJarSettings: _*)

sbtdocker.Plugin.dockerSettings

// Make docker depend on the package task, which generates a jar file of the application code
docker <<= docker.dependsOn(oneJar)

// Docker build
dockerfile in docker := {
  import sbtdocker.Instructions._
  import sbtdocker._
  val jarFile = artifactPath.in(Compile, oneJar).value
  immutable.Dockerfile.empty
    .from("dit4c/dit4c-platform-basejre")
    .add(jarFile, "/opt/dit4c-gatehouse.jar")
    .cmd("sh", "-c", "cd /opt && exec java -jar /opt/dit4c-gatehouse.jar -i 0.0.0.0 -H unix:///var/run/docker.sock -s $PORTAL_URL/public-keys")
    .expose(8080)
}

// Set a custom image name
imageName in docker := {
  ImageName(namespace = Some("dit4c"),
    repository = "dit4c-platform-gatehouse",
    tag = Some(version.value))
}

ReleaseKeys.publishArtifactsAction := docker.value
