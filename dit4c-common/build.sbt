name  := "dit4c-common"

crossScalaVersions := Nil

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val akkaV = "2.4.2"
  val specs2V = "3.6.4"
  Seq(
    "com.typesafe.akka"   %%  "akka-http-core"  % akkaV,
    "com.typesafe.akka"   %%  "akka-http-testkit" % akkaV % "test",
    "org.specs2"          %%  "specs2-core"     % specs2V % "test",
    "org.specs2"          %%  "specs2-mock"     % specs2V % "test",
    "dnsjava"             % "dnsjava"           % "2.1.7" % "test"
  )
}

fork := true

scalacOptions ++= Seq("-feature")
