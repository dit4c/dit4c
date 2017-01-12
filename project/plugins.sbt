import SharedDependencyVersions._

// Comment to get more information during initialization
logLevel := Level.Warn

////////////////////
// Play Framework //
////////////////////

// The Typesafe repository
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % playV)

///////////
// Other //
///////////

// Incorporate build info into runtime
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.6.1")

// Bundle to single jar
addSbtPlugin("org.scala-sbt.plugins" % "sbt-onejar" % "0.8")

libraryDependencies ++= Seq(
  "com.github.scala-incubator.io" %% "scala-io-core" % "0.4.3",
  "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.3"
)

// Release management
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.3")

// Coverage tools
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.4.0")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.1.0")
