organization  := "dit4c.gatehouse"

version       := "0.1"

scalaVersion  := "2.10.3"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val akkaV = "2.3.0"
  val sprayV = "1.3.1"
  Seq(
    "io.spray"            %   "spray-can"       % sprayV,
    "io.spray"            %   "spray-client"    % sprayV,
    "io.spray"            %%  "spray-json"      % "1.2.5",
    "io.spray"            %   "spray-routing"   % sprayV,
    "io.spray"            %   "spray-testkit"   % sprayV  % "test",
    "com.typesafe.akka"   %%  "akka-actor"      % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"    % akkaV   % "test",
    "org.specs2"          %%  "specs2-core"     % "2.3.7" % "test",
    "com.nimbusds"        %   "nimbus-jose-jwt" % "2.22.1",
    "com.github.scopt"    %%  "scopt"           % "3.2.0"
  )
}

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
  output.truncate(0)
  stubScript copyDataTo output
  payload copyDataTo output
  // Set as executable
  outputFile.setExecutable(true)
  // Return path
  outputFile.getAbsolutePath
}
