// Comment to get more information during initialization
logLevel := Level.Warn

// The Typesafe repository 
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.3.1")

// Eclipse plugin
addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.5.0")

// Release management
addSbtPlugin("com.github.gseitz" % "sbt-release" % "0.8.3")

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

// * Mocha
addSbtPlugin("com.typesafe.sbt" % "sbt-mocha" % "1.0.0")

// * RequireJS
addSbtPlugin("com.typesafe.sbt" % "sbt-rjs" % "1.0.3")
