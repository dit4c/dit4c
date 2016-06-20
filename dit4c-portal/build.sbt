import play.core.PlayVersion.{current => playV}

name := "dit4c-portal"

libraryDependencies ++= Seq(
  "com.softwaremill.macwire"  %%  "macros"            % "2.2.3"   % "provided",
  "org.specs2"                %%  "specs2-core"       % "3.6"     % "test",
  "org.specs2"                %%  "specs2-scalacheck" % "3.6"     % "test"
)

// Bower WebJars
libraryDependencies ++= {
  val __ = "org.webjars.bower" 
  Seq(
    __ %  "webcomponentsjs"        % "0.7.21",
    __ %  "github-com-PolymerElements-iron-flex-layout"       % "1.3.1",
    __ %  "github-com-PolymerElements-paper-button"           % "1.0.12",
    __ %  "github-com-PolymerElements-paper-input"            % "1.1.11",
    __ %  "github-com-PolymerElements-paper-ripple"           % "1.0.5"
  )
} 

scalacOptions ++= Seq("-feature")

routesGenerator := InjectedRoutesGenerator
