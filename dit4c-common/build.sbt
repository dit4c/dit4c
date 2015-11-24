name  := "dit4c-common"

crossScalaVersions := Nil

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val akkaHttpV = "2.0-M1"
  val specs2V = "3.6.4"
  Seq(
    "com.typesafe.akka"   %%  "akka-http-experimental" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-testkit-experimental" % akkaHttpV % "test",
    "org.specs2"          %%  "specs2-core"     % specs2V % "test",
    "org.specs2"          %%  "specs2-mock"     % specs2V % "test"
  )
}

fork := true

scalacOptions ++= Seq("-feature")
