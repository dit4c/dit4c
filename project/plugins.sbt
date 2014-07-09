// Comment to get more information during initialization
logLevel := Level.Warn

// The Typesafe repository 
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.3.0")

// Eclipse plugin
addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.4.0")

// Release management
addSbtPlugin("com.github.gseitz" % "sbt-release" % "0.8.3")

/////////////////////
// sbt-web plugins //
/////////////////////

// * Less
addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.0.0")

// * JsHint
addSbtPlugin("com.typesafe.sbt" % "sbt-jshint" % "1.0.1")

// * Mocha
addSbtPlugin("com.typesafe.sbt" % "sbt-mocha" % "1.0.0")
