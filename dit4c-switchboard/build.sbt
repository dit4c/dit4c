name := "dit4c-switchboard"

fork in run := true

connectInput in run := true

dependencyOverrides := Set(
  "org.scala-lang" %  "scala-library"  % scalaVersion.value,
  "org.scala-lang" %  "scala-reflect"  % scalaVersion.value,
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
    "com.github.scopt"    %%  "scopt"           % "3.2.0"
  )
}

net.virtualvoid.sbt.graph.Plugin.graphSettings