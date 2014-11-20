// Comment to get more information during initialization
logLevel := Level.Warn

////////////////////
// Play Framework //
////////////////////

// The Typesafe repository
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.3.5")

/////////////////////
// sbt-web plugins //
/////////////////////

// * CoffeeScript
addSbtPlugin("com.typesafe.sbt" % "sbt-coffeescript" % "1.0.0")

// * Digest
addSbtPlugin("com.typesafe.sbt" % "sbt-digest" % "1.0.0")

// * Gzip
addSbtPlugin("com.typesafe.sbt" % "sbt-gzip" % "1.0.0")

// * Less
addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.0.0")

// * JsHint
addSbtPlugin("com.typesafe.sbt" % "sbt-jshint" % "1.0.1")

// * RequireJS
addSbtPlugin("com.typesafe.sbt" % "sbt-rjs" % "1.0.3")

///////////
// Spray //
///////////

addSbtPlugin("io.spray" % "sbt-revolver" % "0.7.1")

///////////
// Other //
///////////

// Eclipse helper
addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.4.0")

// Bundle to single jar
addSbtPlugin("org.scala-sbt.plugins" % "sbt-onejar" % "0.8")

libraryDependencies ++= Seq(
  "com.github.scala-incubator.io" %% "scala-io-core" % "0.4.2",
  "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.2"
)

// Release management
addSbtPlugin("com.github.gseitz" % "sbt-release" % "0.8.5")

// Dependency graph
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.4")
