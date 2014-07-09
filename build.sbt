import sbtrelease._
import ReleaseStateTransformations._

name := "dit4c-highcommand"

version := "0.1-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.1"

libraryDependencies ++= Seq(
  cache,
  ws,
  "com.google.inject"   %   "guice"           % "3.0",
  "com.nimbusds"        %   "nimbus-jose-jwt" % "2.22.1",
  // WebJars for client-side dependencies
  "org.webjars" %% "webjars-play" % "2.3.0",
  // jQuery
  "org.webjars" % "jquery" % "1.11.1",
  "org.webjars" % "jquery-cookie" % "1.4.0",
  // Bootstrap
  "org.webjars" % "bootstrap" % "3.1.1-1",
  // Ember.js
  "org.webjars" % "handlebars" % "1.3.0",
  "org.webjars" % "emberjs" % "1.5.0",
  "org.webjars" % "emberjs-data" % "1.0.0-beta.4"
)

resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/groups/staging/"

version <<= version in ThisBuild

// Produce scala object that knows the app version
sourceGenerators in Compile <+= (sourceManaged in Compile, version, cacheDirectory) map { (dir, v, cacheDir) =>
  val cache =
    FileFunction.cached(cacheDir / "version", inStyle = FilesInfo.hash, outStyle = FilesInfo.hash) { in: Set[File] =>
      val file = in.toSeq.head
      val content =
        s"""|package helpers
            |object AppVersion { 
            |  override def toString = "$v"
            |}""".stripMargin
      IO.write(file, content);
      Set(file)
    }
  cache(Set( dir / "helpers" / "AppVersion.scala" )).toSeq
}

// Clojure compiler options to handle Ember.js, from:
// http://stackoverflow.com/questions/22137767/playframework-requirejs-javascript-files-not-being-optimized
val closureOptions = {
  import com.google.javascript.jscomp._
  val root = new java.io.File(".")
  val opts = new CompilerOptions()
  opts.closurePass = true
  opts.setProcessCommonJSModules(true)
  opts.setCommonJSModulePathPrefix(root.getCanonicalPath + "/app/assets/javascripts/")
  opts.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT5_STRICT)
  CompilationLevel.WHITESPACE_ONLY.setOptionsForCompilationLevel(opts)
  opts
}

releaseSettings

ReleaseKeys.releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,              // : ReleaseStep
  inquireVersions,                        // : ReleaseStep
  runClean,                               // : ReleaseStep
  runTest,                                // : ReleaseStep
  setReleaseVersion,                      // : ReleaseStep
  commitReleaseVersion,                   // : ReleaseStep, performs the initial git checks
  tagRelease,                             // : ReleaseStep
  setNextVersion,                         // : ReleaseStep
  commitNextVersion,                      // : ReleaseStep
  pushChanges                             // : ReleaseStep, also checks that an upstream branch is properly configured
)

//closureCompilerOptions ++= Seq("--language_in", "ECMASCRIPT5")

//play.Project.playScalaSettings ++ closureCompilerSettings(closureOptions)
