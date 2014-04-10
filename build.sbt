name := "dit4c-highcommand"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  cache,
  "com.google.inject" % "guice" % "3.0"
)     

resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

play.Project.playScalaSettings
