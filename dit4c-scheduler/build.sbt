import SharedDependencyVersions._

name := "dit4c-scheduler"

crossScalaVersions := Nil

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  Seq(
    "ch.qos.logback"      %   "logback-classic"       % "1.1.7",
    "com.typesafe.akka"   %%  "akka-actor"            % akkaV,
    "com.jcraft"          %   "jsch"                  % "0.1.53",
    "com.github.scopt"    %%  "scopt"                 % "3.4.0",
    "de.heikoseeberger"   %%  "akka-http-play-json"   % "1.7.0",
    "org.bouncycastle"    %   "bcpkix-jdk15on"        % "1.54",
    "org.specs2"          %%  "specs2-core"           % specs2V % "test",
    "org.specs2"          %%  "specs2-matcher-extra"  % specs2V % "test",
    "org.specs2"          %%  "specs2-scalacheck"     % specs2V % "test",
    "org.apache.sshd"     %   "apache-sshd"           % "1.2.0" % "test"
      exclude("org.slf4j", "slf4j-jdk14")
  )
}

fork := true

scalacOptions ++= Seq("-feature")

packSettings

packMain := Map("dit4c-scheduler" -> "dit4c.scheduler.Main")

// Produce scala object that knows the app version
sourceGenerators in Compile <+= (sourceManaged in Compile, name, version, cacheDirectory) map { (dir, name, version, cacheDir) =>
  val cache =
    FileFunction.cached(cacheDir / "version", inStyle = FilesInfo.hash, outStyle = FilesInfo.hash) { in: Set[File] =>
      val file = in.toSeq.head
      val content =
        s"""|package dit4c.scheduler
            |object AppMetadataImpl extends utils.AppMetadata {
            |  override def name = "$name"
            |  override def version = "$version"
            |}""".stripMargin
      IO.write(file, content);
      Set(file)
    }
  cache(Set( dir / "dit4c" / "scheduler" / "AppMetadataImpl.scala" )).toSeq
}
