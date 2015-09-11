import sbtdocker.{ImageName, Dockerfile}
import DockerKeys._

name := "dit4c-switchboard"

fork in run := true

connectInput in run := true

dependencyOverrides := Set(
  "org.scala-lang" %  "scala-library"  % scalaVersion.value,
  "org.scala-lang" %  "scala-compiler" % scalaVersion.value
)

libraryDependencies ++= {
  val akkaV = "2.3.6"
  val akkaHttpV = "1.0"
  val specs2V = "3.6.4"
  Seq(
    "com.typesafe.akka"   %%  "akka-actor"      % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"    % akkaV   % "test",
    "com.typesafe.akka"   %%  "akka-http-experimental" % akkaHttpV,
    "com.typesafe.play"   %%  "play-json"       % "2.4.3",
    "ch.qos.logback"      %   "logback-classic" % "1.1.2",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
    "org.specs2"          %%  "specs2-core"     % specs2V % "test",
    "org.specs2"          %%  "specs2-matcher-extra" % specs2V % "test",
    "org.specs2"          %%  "specs2-mock"     % specs2V % "test",
    "org.scalatra.scalate" %% "scalate-core"    % "1.7.1",
    "com.github.scopt"    %%  "scopt"           % "3.2.0",
    "org.bouncycastle"    %   "bcpkix-jdk15on"  % "1.52"
  )
}

net.virtualvoid.sbt.graph.Plugin.graphSettings

seq(com.github.retronym.SbtOneJar.oneJarSettings: _*)

packSettings

packMain := Map("dit4c-switchboard" -> "dit4c.switchboard.Boot")

sbtdocker.Plugin.dockerSettings

// Make docker depend on the package task, which generates a jar file of the application code
docker <<= docker.dependsOn(pack)

// Docker build
dockerfile in docker := {
  import sbtdocker.Instructions._
  import sbtdocker._
  val appDir = (packTargetDir / "pack").value
  immutable.Dockerfile.empty
    .from("dit4c/dit4c-platform-base")
    .run("bash", "-c",
      """
      rpm --rebuilddb &&
      yum -y install java-1.8.0-openjdk nginx &&
      rm /etc/nginx/conf.d/*.conf
      """)
    .add(appDir, "/opt/dit4c-switchboard")
    .run("chmod", "+x", "/opt/dit4c-switchboard/bin/dit4c-switchboard")
    .volume("/etc/ssl")
    .cmd("sh", "-c",
      """
      SSL_OPTS=""
      KEY_FILE="/etc/ssl/server.key"
      CERT_FILE="/etc/ssl/server.crt"
      if [ -e $KEY_FILE -o -e $CERT_FILE ]; then
        SSL_OPTS="-k $KEY_FILE -c $CERT_FILE"
      fi
      /opt/dit4c-switchboard/bin/dit4c-switchboard -f $DIT4C_ROUTE_FEED -p 8080 -d $DIT4C_DOMAIN $SSL_OPTS
      """
    )
    .expose(8080)
}

// Set a custom image name
imageName in docker := {
  ImageName(namespace = Some("dit4c"),
    repository = "dit4c-platform-switchboard",
    tag = Some(version.value))
}

ReleaseKeys.publishArtifactsAction := docker.value