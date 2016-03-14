import sbtdocker.{ImageName, Dockerfile}
import DockerKeys._

name := "dit4c-highcommand"

libraryDependencies ++= Seq(
  cache,
  filters,
  ws,
  specs2 % "test",
  "com.google.inject"   %   "guice"           % "3.0",
  "com.nimbusds"        %   "nimbus-jose-jwt" % "4.8",
  "com.osinka.slugify"  %%  "slugify"         % "1.2.1",
  "net.nikore.etcd"     %%  "scala-etcd"      % "0.7",
  "org.gnieh"           %%  "sohva-dm"        % "1.1.0-dit4c-pre1",
  "com.github.rjeschke" %   "txtmark"         % "0.13",
  "com.typesafe.akka"   %%  "akka-agent"      % "2.3.11",
  "org.mapdb"           %   "mapdb"           % "1.0.8",
  "com.typesafe.akka"   %%  "akka-testkit"    % "2.3.11"    % "test",
  "org.specs2"          %%  "specs2-core"     % "3.6"       % "test",
  "org.specs2"          %%  "specs2-junit"    % "3.6"       % "test",
  "org.specs2"          %%  "specs2-scalacheck" % "3.6"     % "test",
  "com.couchbase.lite" % "couchbase-lite-java" % "1.2.0" % "test",
  "com.couchbase.lite" % "couchbase-lite-java-forestdb" % "1.2.0" % "test",
  "com.couchbase.lite" % "couchbase-lite-java-listener" % "1.2.0" % "test",
  "com.couchbase.lite" % "couchbase-lite-java-javascript" % "1.2.0" % "test",
  // WebJars for client-side dependencies
  "org.webjars"         %%  "webjars-play"    % "2.4.0-2",
  // AngularJS
  "org.webjars" % "angular-ui-bootstrap" % "0.12.0",
  // Bootstrap & Font Awesome
  "org.webjars" % "bootstrap" % "3.3.5",
  "org.webjars" % "font-awesome" % "4.4.0",
  // domReady
  "org.webjars" % "requirejs-domready" % "2.0.1-2"
)

resolvers ++= Seq(
  "DIT4C BinTray" at "http://dl.bintray.com/dit4c/repo/",
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
  "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases",
  "Sonatype snapshots" at "https://oss.sonatype.org/content/groups/staging/")

version <<= version in ThisBuild

crossScalaVersions := Nil

scalacOptions ++= Seq("-feature")

// Produce scala object that knows the app version
sourceGenerators in Compile <+= (sourceManaged in Compile, version, cacheDirectory) map { (dir, v, cacheDir) =>
  val cache =
    FileFunction.cached(cacheDir / "version", inStyle = FilesInfo.hash, outStyle = FilesInfo.hash) { in: Set[File] =>
      val file = in.toSeq.head
      val content =
        s"""|package helpers
            |object AppVersion {
            |  override def toString = "$v"
            |}""".stripMargin
      IO.write(file, content);
      Set(file)
    }
  cache(Set( dir / "helpers" / "AppVersion.scala" )).toSeq
}

pipelineStages := Seq(digest, gzip)

sbtdocker.Plugin.dockerSettings

net.virtualvoid.sbt.graph.Plugin.graphSettings

// Make docker depend on the package task, which generates a jar file of the application code
docker <<= docker.dependsOn(com.typesafe.sbt.packager.Keys.stage)

// Docker build
dockerfile in docker := {
 import sbtdocker.Instructions._
 import sbtdocker._
 val stageDir =
   com.typesafe.sbt.packager.Keys.stagingDirectory.in(Universal).value
 val dockerResources = baseDirectory.value / "docker"
 val configs = dockerResources / "etc"
 val prodConfig = dockerResources / "opt" / "dit4c-highcommand" / "prod.conf"
 immutable.Dockerfile.empty
   .from("dit4c/dit4c-platform-basejre")
   .add(stageDir, "/opt/dit4c-highcommand/")
   .add(prodConfig, "/opt/dit4c-highcommand/prod.conf")
   .add(configs, "/etc")
   .run("chmod", "+x", "/opt/dit4c-highcommand/bin/dit4c-highcommand")
   .run("adduser", "-h", "/opt/dit4c-highcommand", "-S", "dit4c")
   .user("dit4c")
   .cmd("/opt/dit4c-highcommand/bin/dit4c-highcommand",
        "-Dconfig.file=/opt/dit4c-highcommand/prod.conf",
        "-Dpidfile.path=/dev/null")
   .expose(9000)
}

// Set a custom image name
imageName in docker := {
 ImageName(namespace = Some("dit4c"),
   repository = "dit4c-platform-highcommand",
   tag = Some(version.value))
}

ReleaseKeys.publishArtifactsAction := docker.value
