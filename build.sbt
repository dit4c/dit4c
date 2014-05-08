name := "dit4c-highcommand"

version := "0.1-SNAPSHOT"

libraryDependencies ++= Seq(
  cache,
  "com.google.inject"   %   "guice"           % "3.0",
  "com.nimbusds"        %   "nimbus-jose-jwt" % "2.22.1",
  "org.gnieh"           %%  "sohva-client"    % "0.5",
  "org.gnieh"           %%  "sohva-testing"   % "0.4"
)

resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

play.Project.playScalaSettings
