import sbtdocker.{ImageName, Dockerfile}
import DockerKeys._

name  := "dit4c-machineshop"

scalaVersion  := "2.10.4" // Stuck until spray-json updates

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val akkaV = "2.3.4"
  val sprayV = "1.3.1"
  Seq(
    "io.spray"            %   "spray-can"       % sprayV,
    "io.spray"            %   "spray-client"    % sprayV,
    "io.spray"            %%  "spray-json"      % "1.2.6",
    "io.spray"            %   "spray-routing"   % sprayV,
    "io.spray"            %   "spray-testkit"   % sprayV  % "test",
    "com.typesafe.akka"   %%  "akka-actor"      % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"    % akkaV   % "test",
    "org.specs2"          %%  "specs2-core"     % "2.3.7" % "test",
    "org.codehaus.groovy" %   "groovy-all"      % "1.8.8" % "compile",
    "co.freeside"         %   "betamax"         % "1.1.2" % "test",
    "com.nimbusds"        %   "nimbus-jose-jwt" % "2.22.1",
    "com.github.scopt"    %%  "scopt"           % "3.2.0"
  )
}

fork := true

javaOptions += "-Dfoo=bar"

// Set Jetty in Betamax to use java logging
javaOptions += "-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog"

javaOptions += "-Djava.util.logging.config.file=logging.properties"

Revolver.settings

seq(com.github.retronym.SbtOneJar.oneJarSettings: _*)

// Build runnable executable
lazy val generateExecutable = taskKey[String]("Creates a single-file Linux executable using one-jar and a stub script.")

generateExecutable := {
  import scalax.io._
  val outputFile = target.value / "executable" / s"${name.value}-${version.value}"
  // Based on https://coderwall.com/p/ssuaxa
  val payload = Resource.fromFile(oneJar.value)
  val stubScript = Resource.fromFile(
    (resourceDirectory in Compile).value / "exec_stub.sh")
  val output = Resource.fromFile(outputFile)
  // Delete any existing content, then write stub followed by payload
  for {
    processor <- output.outputProcessor
    out = processor.asOutput
  } {
    out.write(stubScript.bytes)
    out.write(payload.bytes)
  }
  // Set as executable
  outputFile.setExecutable(true)
  // Return path
  outputFile.getAbsolutePath
}

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
    .from("dit4c/dit4c-platform-base")
    .run("yum", "-y", "install", "java-1.7.0-openjdk-headless", "socat")
    .add(jarFile, "/opt/dit4c-machineshop.jar")
    .add(configs, "/etc")
    .cmd("/usr/bin/supervisord", "-n")
    .expose(8080)
}

// Set a custom image name
imageName in docker := {
  ImageName(namespace = Some("dit4c"),
    repository = "dit4c-platform-machineshop",
    tag = Some(version.value))
}