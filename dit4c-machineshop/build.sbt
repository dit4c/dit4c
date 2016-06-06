name  := "dit4c-machineshop"

crossScalaVersions := Nil

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val akkaV = "2.4.2"
  val scalaioV = "0.4.3-1"
  val specs2V = "3.6.4"
  Seq(
    "com.github.scala-incubator.io" %% "scala-io-core" % scalaioV,
    "com.github.scala-incubator.io" %% "scala-io-file" % scalaioV,
    "net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
    "com.github.docker-java" % "docker-java"    % "3.0.0",
    "ch.qos.logback"      %   "logback-classic" % "1.1.3",
    "com.typesafe.akka"   %%  "akka-actor"      % akkaV,
    "com.typesafe.akka"   %%  "akka-http-spray-json-experimental" % akkaV,
    "com.typesafe.akka"   %%  "akka-http-xml-experimental" % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"    % akkaV   % "test",
    "com.typesafe.akka"   %%  "akka-http-testkit" % akkaV % "test",
    "org.specs2"          %%  "specs2-core"     % specs2V % "test",
    "org.specs2"          %%  "specs2-mock"     % specs2V % "test",
    "org.mockito"         %   "mockito-all"     % "1.10.8" % "test",
    "org.codehaus.groovy" %   "groovy-all"      % "1.8.8" % "compile",
    "com.nimbusds"        %   "nimbus-jose-jwt" % "2.22.1",
    "com.github.scopt"    %%  "scopt"           % "3.2.0",
    "com.google.code.findbugs" % "jsr305"       % "3.0.1"
  )
}

resolvers ++= Seq(
  "Sonatype snapshots" at "https://oss.sonatype.org/content/groups/staging/")

fork := true

scalacOptions ++= Seq("-feature")

// Set Jetty in Betamax to use java logging
javaOptions += "-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog"

javaOptions += "-Djava.util.logging.config.file=logging.properties"

Revolver.settings

packSettings

packMain := Map("dit4c-machineshop" -> "dit4c.machineshop.Boot")

enablePlugins(sbtdocker.DockerPlugin)

// Make docker depend on the package task
docker <<= docker.dependsOn(pack)

// Docker build
dockerfile in docker := {
  import sbtdocker.Instructions._
  import sbtdocker._
  val appDir = (packTargetDir / "pack").value
  val dockerResources = baseDirectory.value / "src" / "main" / "docker"
  val configs = dockerResources / "etc"
  immutable.Dockerfile.empty
    .from("alpine:3.3")
    .run("sh", "-c", "apk add --update openjdk8-jre-base dbus && rm -rf /var/cache/apk/*")
    .add(appDir, "/opt/dit4c-machineshop")
    .run("chmod", "+x", "/opt/dit4c-machineshop/bin/dit4c-machineshop")
    .volume("/etc/dit4c-machineshop")
    .cmd("sh", "-c", """
      set -e
      JAVA_OPTS="-Dsun.net.inetaddr.ttl=60"
      dbus-uuidgen --ensure=/etc/dit4c-machineshop/machine-id
      exec /opt/dit4c-machineshop/bin/dit4c-machineshop -i 0.0.0.0 -p 8080 -s $PORTAL_URL/public-keys --server-id-seed-file /etc/dit4c-machineshop/machine-id --image-update-interval 900 --known-images-file /etc/dit4c-machineshop/known_images.json
      """)
    .expose(8080)
}

// Set a custom image name
imageName in docker := {
  ImageName(namespace = Some("dit4c"),
    repository = "dit4c-platform-machineshop",
    tag = Some(version.value))
}

ReleaseKeys.publishArtifactsAction := docker.value
