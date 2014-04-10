name := "dit4c-highcommand"

version := "0.1-SNAPSHOT"

libraryDependencies ++= Seq(
  cache,
  "com.google.inject"   %   "guice"           % "3.0",
  "com.nimbusds"        %   "nimbus-jose-jwt" % "2.22.1"
)

resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

play.Project.playScalaSettings
