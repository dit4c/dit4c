name := "dit4c-gatehouse"

crossScalaVersions := Nil

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

fork in run := true

libraryDependencies ++= {
  val akkaV = "2.4.2"
  Seq(
    "com.typesafe.akka"   %%  "akka-actor"      % akkaV,
    "com.typesafe.akka"   %%  "akka-http-spray-json-experimental" % akkaV,
    "com.typesafe.akka"   %%  "akka-http-xml-experimental" % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"    % akkaV   % "test",
    "com.typesafe.akka"   %%  "akka-http-testkit" % akkaV % "test",
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

enablePlugins(sbtdocker.DockerPlugin)

// Make docker depend on the package task, which generates a jar file of the application code
docker <<= docker.dependsOn(pack)

// Docker build
dockerfile in docker := {
  import sbtdocker.Instructions._
  import sbtdocker._
  val appDir = (packTargetDir / "pack").value
  immutable.Dockerfile.empty
    .from("alpine:3.3")
    .run("sh", "-c", "apk add --update openjdk8-jre-base && rm -rf /var/cache/apk/*")
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
