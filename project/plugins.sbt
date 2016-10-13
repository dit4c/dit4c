// Comment to get more information during initialization
logLevel := Level.Warn

////////////////////
// Play Framework //
////////////////////

// The Typesafe repository
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.5.9")

///////////
// Other //
///////////

// Bundle to single jar
addSbtPlugin("org.scala-sbt.plugins" % "sbt-onejar" % "0.8")

libraryDependencies ++= Seq(
  "com.github.scala-incubator.io" %% "scala-io-core" % "0.4.3",
  "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.3"
)

// Release management
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.3")

// Gather dependencies without squashing to single JAR
addSbtPlugin("org.xerial.sbt" % "sbt-pack" % "0.8.0")

// Coverage tools
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.4.0")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.1.0")
