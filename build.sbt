name := "dit4c-highcommand"

version := "0.1-SNAPSHOT"

libraryDependencies ++= Seq(
  cache,
  "com.google.inject"   %   "guice"           % "3.0",
  "com.nimbusds"        %   "nimbus-jose-jwt" % "2.22.1",
  // WebJars for client-side dependencies
  "org.webjars" %% "webjars-play" % "2.2.2" exclude("org.scala-lang", "scala-library"),
  // jQuery
  "org.webjars" % "jquery" % "1.11.1",
  // Bootstrap
  "org.webjars" % "bootstrap" % "3.1.1-1",
  // Ember.js
  "org.webjars" % "handlebars" % "1.3.0",
  "org.webjars" % "emberjs" % "1.5.0",
  "org.webjars" % "emberjs-data" % "1.0.0-beta.4"
)

resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/groups/staging/"

play.Project.playScalaSettings
