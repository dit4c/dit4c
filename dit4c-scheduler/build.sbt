import SharedDependencyVersions._

name := "dit4c-scheduler"

crossScalaVersions := Nil

scalacOptions := Seq(
  "-unchecked",
  "-deprecation",
  "-encoding", "utf8",
  "-Xfatal-warnings")

libraryDependencies ++= {
  Seq(
    "com.trueaccord.scalapb" %% "scalapb-runtime" % scalapbV % "protobuf",
    "ch.qos.logback"      %   "logback-classic"       % logbackV,
    "com.typesafe.akka"   %%  "akka-actor"            % akkaV,
    "com.typesafe.akka"   %%  "akka-persistence"      % akkaV,
    "com.typesafe.akka"   %%  "akka-slf4j"            % akkaV,
    "com.jcraft"          %   "jsch"                  % "0.1.53",
    "com.github.scopt"    %%  "scopt"                 % "3.4.0",
    "de.heikoseeberger"   %%  "akka-http-play-json"   % "1.7.0",
    "org.iq80.leveldb"            % "leveldb"         % "0.7",
    "org.fusesource.leveldbjni"   % "leveldbjni-all"  % "1.8",
    "org.specs2"          %%  "specs2-core"           % specs2V % "test",
    "org.specs2"          %%  "specs2-matcher-extra"  % specs2V % "test",
    "org.specs2"          %%  "specs2-scalacheck"     % specs2V % "test",
    "com.typesafe.akka"   %%  "akka-http-testkit"     % akkaV % "test",
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.3.0" % "test",
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

// Generate protobuf classes for Akka serialization
PB.targets in Compile := Seq(
  scalapb.gen(grpc = false) -> (sourceManaged in Compile).value
)

// Download rkt for testing
resourceGenerators in Test <+=
  (resourceManaged in Test, name, version, streams) map { (dir, n, v, s) =>
    import scala.sys.process._
    val rktVersion = "1.16.0"
    val rktDir = dir / "rkt"
    val rktExecutable = rktDir / "rkt"
    s.log.debug(s"Checking for rkt $rktVersion tarball")
    if (!rktDir.isDirectory || !(rktExecutable).isFile) {
      IO.withTemporaryDirectory { tmpDir =>
        val rktTarballUrl = new java.net.URL(
          s"https://github.com/coreos/rkt/releases/download/v${rktVersion}/rkt-v${rktVersion}.tar.gz")
        val rktTarball = tmpDir / s"rkt-v${rktVersion}.tar.gz"
        s.log.info(s"Downloading rkt $rktVersion")
        IO.download(rktTarballUrl, rktTarball)
        val sbtLogger = ProcessLogger(s.log.info(_), s.log.warn(_))
        Process(
          Seq("tar", "xzf", rktTarball.getAbsolutePath),
          cwd=Some(tmpDir)).!<(sbtLogger)
        IO.delete(rktDir)
        IO.copyDirectory(tmpDir / s"rkt-v${rktVersion}", rktDir)
        IO.delete(tmpDir)
      }
    }
    IO.listFiles(rktDir).toSeq
  }
