import SharedDependencyVersions._
import play.core.PlayVersion.{current => playV}

name := "dit4c-portal"

libraryDependencies ++= Seq(
  "com.softwaremill.macwire"  %%  "macros"  % macwireV    % "provided",
  "com.softwaremill.macwire"  %%  "util"    % macwireV,
  "de.heikoseeberger"         %%  "akka-http-play-json"   % "1.7.0",
  "com.typesafe.akka"         %%  "akka-http-core"        % akkaV,
  "com.typesafe.akka"         %%  "akka-persistence"      % akkaV,
  "org.iq80.leveldb"          %   "leveldb"               % "0.7",
  "org.fusesource.leveldbjni" %   "leveldbjni-all"        % "1.8",
  "com.pauldijou"             %%  "jwt-play-json"         % "0.7.1",
  "com.mohiva"                %%  "play-silhouette"       % "4.0.0-RC1",
  "com.mohiva"                %%  "play-silhouette-crypto-jca" % "4.0.0-RC1",
  "com.iheart"                %%  "ficus"                 % "1.2.3",
  ws,
  specs2,
  "org.specs2"                %%  "specs2-core"       % specs2V % "test",
  "org.specs2"                %%  "specs2-scalacheck" % specs2V % "test"
)

// Bower WebJars
libraryDependencies ++= {
  val __ = "org.webjars.bower" 
  Seq(
    __ %  "webcomponentsjs"        % "0.7.21",
    __ %  "github-com-web-animations-web-animations-js"             % "2.2.1",
    __ %  "github-com-PolymerLabs-promise-polyfill"                 % "1.0.0",
    __ %  "github-com-PolymerElements-iron-flex-layout"             % "1.3.1",
    __ %  "github-com-PolymerElements-iron-form"                    % "1.0.16",
    __ %  "github-com-PolymerElements-iron-iconset-svg"             % "1.0.9",
    __ %  "github-com-PolymerElements-iron-image"                   % "1.0.4",
    __ %  "github-com-PolymerElements-iron-media-query"             % "1.0.7",
    __ %  "github-com-PolymerElements-iron-overlay-behavior"        % "1.8.0",
    __ %  "github-com-PolymerElements-iron-resizable-behavior"      % "1.0.3",
    __ %  "github-com-PolymerElements-iron-scroll-target-behavior"  % "1.0.6",
    __ %  "github-com-PolymerElements-neon-animation"               % "1.2.3"
      exclude(__, "github-com-web-animations-web-animations-js"),
    __ %  "github-com-PolymerElements-paper-button"                 % "1.0.12",
    __ %  "github-com-PolymerElements-paper-card"                   % "1.0.8",
    __ %  "github-com-PolymerElements-paper-dropdown-menu"          % "1.2.2",
    __ %  "github-com-PolymerElements-paper-fab"                    % "1.1.0",
    __ %  "github-com-PolymerElements-paper-icon-button"            % "1.0.6",
    __ %  "github-com-PolymerElements-paper-input"                  % "1.1.11",
    __ %  "github-com-PolymerElements-paper-item"                   % "1.2.1",
    __ %  "github-com-PolymerElements-paper-listbox"                % "1.1.2",
    __ %  "github-com-PolymerElements-paper-menu-button"            % "1.1.1",
    __ %  "github-com-PolymerElements-paper-ripple"                 % "1.0.5",
    __ %  "github-com-PolymerElements-app-layout"                   % "0.9.1"
      exclude(__, "github-com-polymerelements-iron-media-query")
      exclude(__, "github-com-polymerelements-iron-scroll-target-behavior")
  )
} 

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xfatal-warnings",
//  "-Xlint",
  "-Ywarn-dead-code",
  "-Ywarn-inaccessible",
  "-Ywarn-nullary-override",
  "-Ywarn-numeric-widen",
//  "-Ywarn-unused-import",
  "-Xfuture"
)

routesGenerator := InjectedRoutesGenerator

// Speed up resolution times
updateOptions := updateOptions.value.withCachedResolution(true)
