import sbtdocker.{ImageName, Dockerfile}
import DockerKeys._

name := "dit4c-highcommand"

libraryDependencies ++= Seq(
  cache,
  filters,
  ws,
  "com.google.inject"   %   "guice"           % "3.0",
  "com.nimbusds"        %   "nimbus-jose-jwt" % "2.26.1",
  "net.nikore.etcd"     %%  "scala-etcd"      % "0.7",
  "org.gnieh"           %%  "sohva-dm"        % "1.1.0-dit4c-pre1",
  "com.typesafe.akka"   %%  "akka-agent"      % "2.3.6"     % "test",
  "com.typesafe.akka"   %%  "akka-testkit"    % "2.3.6"     % "test",
  "org.specs2"          %%  "specs2-scalacheck" % "2.3.12"  % "test",
  // WebJars for client-side dependencies
  "org.webjars" %% "webjars-play" % "2.3.0",
  // AngularJS
  "org.webjars" % "angular-ui-bootstrap" % "0.12.0",
  // Bootstrap & Font Awesome
  "org.webjars" % "bootstrap" % "3.3.1",
  "org.webjars" % "font-awesome" % "4.2.0",
  // domReady
  "org.webjars" % "requirejs-domready" % "2.0.1-2"
)

resolvers ++= Seq(
  "DIT4C BinTray" at "http://dl.bintray.com/dit4c/repo/",
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
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

// Clojure compiler options to handle Ember.js, from:
// http://stackoverflow.com/questions/22137767/playframework-requirejs-javascript-files-not-being-optimized
val closureOptions = {
  import com.google.javascript.jscomp._
  val root = new java.io.File(".")
  val opts = new CompilerOptions()
  opts.closurePass = true
  opts.setProcessCommonJSModules(true)
  opts.setCommonJSModulePathPrefix(root.getCanonicalPath + "/app/assets/javascripts/")
  opts.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT5_STRICT)
  CompilationLevel.WHITESPACE_ONLY.setOptionsForCompilationLevel(opts)
  opts
}

pipelineStages := Seq(rjs, digest, gzip)

sbtdocker.Plugin.dockerSettings

net.virtualvoid.sbt.graph.Plugin.graphSettings

// Make docker depend on the package task, which generates a jar file of the application code
docker <<= docker.dependsOn(com.typesafe.sbt.packager.universal.Keys.stage)

// Docker build
dockerfile in docker := {
 import sbtdocker.Instructions._
 import sbtdocker._
 val stageDir =
   com.typesafe.sbt.packager.universal.Keys.stagingDirectory.in(Universal).value
 val dockerResources = baseDirectory.value / "docker"
 val configs = dockerResources / "etc"
 val prodConfig = dockerResources / "opt" / "dit4c-highcommand" / "prod.conf"
 immutable.Dockerfile.empty
   .from("dit4c/dit4c-platform-base")
   .run("yum", "-y", "install", "java-1.7.0-openjdk-headless")
   .add(stageDir, "/opt/dit4c-highcommand/")
   .add(prodConfig, "/opt/dit4c-highcommand/prod.conf")
   .add(configs, "/etc")
   .run("chmod", "+x", "/opt/dit4c-highcommand/bin/dit4c-highcommand")
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
