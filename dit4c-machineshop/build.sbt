import sbtdocker.{ImageName, Dockerfile}
import DockerKeys._

name  := "dit4c-machineshop"

crossScalaVersions := Nil

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val akkaV = "2.3.6"
  val sprayV = "1.3.3"
  val akkaHttpV = "2.0-M1"
  val scalaioV = "0.4.3-1"
  val specs2V = "3.6.4"
  Seq(
    "com.github.scala-incubator.io" %% "scala-io-core" % scalaioV,
    "com.github.scala-incubator.io" %% "scala-io-file" % scalaioV,
    "net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
    "com.github.docker-java" % "docker-java"    % "2.1.1",
    "com.typesafe.akka"   %%  "akka-http-experimental" % akkaHttpV,
    "io.spray"            %%  "spray-json"      % "1.3.0",
    "ch.qos.logback"      %   "logback-classic" % "1.1.3",
    "com.typesafe.akka"   %%  "akka-http-spray-json-experimental" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-testkit-experimental" % akkaHttpV % "test",
    "com.typesafe.akka"   %%  "akka-http-xml-experimental" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-actor"      % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"    % akkaV   % "test",
    "org.specs2"          %%  "specs2-core"     % specs2V % "test",
    "org.specs2"          %%  "specs2-mock"     % specs2V % "test",
    "org.mockito"         %   "mockito-all"     % "1.10.8" % "test",
    "org.codehaus.groovy" %   "groovy-all"      % "1.8.8" % "compile",
    "com.nimbusds"        %   "nimbus-jose-jwt" % "2.22.1",
    "com.github.scopt"    %%  "scopt"           % "3.2.0",
    "com.google.code.findbugs" % "jsr305"       % "3.0.1"
  )
}

fork := true

scalacOptions ++= Seq("-feature")

// Set Jetty in Betamax to use java logging
javaOptions += "-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog"

javaOptions += "-Djava.util.logging.config.file=logging.properties"

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
  val dockerResources = baseDirectory.value / "src" / "main" / "docker"
  val configs = dockerResources / "etc"
  immutable.Dockerfile.empty
    .from("dit4c/dit4c-platform-basejre")
    .run("opkg-install", "dbus")
    .add(jarFile, "/opt/dit4c-machineshop.jar")
    .volume("/etc/dit4c-machineshop")
    .cmd("sh", "-c", """
      set -e
      JAVA_OPTS="-Dsun.net.inetaddr.ttl=60"
      dbus-uuidgen --ensure=/etc/dit4c-machineshop/machine-id
      exec java -jar /opt/dit4c-machineshop.jar -i 0.0.0.0 -p 8080 -H unix:///var/run/docker.sock -s $PORTAL_URL/public-keys --link dit4c_cnproxy:cnproxy --server-id-seed-file /etc/dit4c-machineshop/machine-id --image-update-interval 900 --known-images-file /etc/dit4c-machineshop/known_images.json
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
