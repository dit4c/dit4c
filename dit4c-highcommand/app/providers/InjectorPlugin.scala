package providers

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try
import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import play.api.Plugin
import play.twirl.api.Html
import providers.auth._
import providers.db.CouchDB
import models.ViewManager
import providers.auth.Identity
import scala.concurrent.ExecutionContext
import gnieh.sohva.{DesignDoc,ViewDoc}
import gnieh.sohva.async._
import play.api.{Configuration, Environment}
import providers.db._
import com.google.inject.Provides
import akka.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import javax.inject.Singleton

class InjectorPlugin(
    environment: Environment,
    configuration: Configuration) extends AbstractModule {
  implicit val ec = play.api.libs.concurrent.Execution.defaultContext

  lazy val log = play.api.Logger
  
  lazy val authProviders = AuthProviders(
      RapidAAFAuthProvider(configuration) ++
      GitHubProvider(configuration) ++
      TwitterProvider(configuration) ++
      DummyProvider(configuration))

  val dbName = configuration.getString("couchdb.database").get

  @Singleton @Provides def dbServerInstance(
      lifecycle: ApplicationLifecycle)(
          implicit system: ActorSystem): CouchDB.Instance = {
    val instance =
      if (configuration.getBoolean("couchdb.testing").getOrElse(false)) {
        new EphemeralCouchDBInstance
      } else if (configuration.getString("couchdb.url").isDefined) {
        new ExternalCouchDBInstance(new java.net.URL(
          configuration.getString("couchdb.url").get))
      } else {
        new PersistentCouchDBInstance("./db", 40000)
      }
    lifecycle.addStopHook { () => 
      instance.disconnect
      Future.successful(())
    }
    instance
  }

  // Make sure a database exists
  @Provides def database(dbServerInstance: CouchDB.Instance) = Await.result(
    for {
      maybeDb <- dbServerInstance.databases(dbName)
      db <- maybeDb match {
        case Some(db) => Future.successful(db)
        case None => dbServerInstance.databases.create(dbName)
      }
      design = db.asSohvaDb.design("main", "javascript")
      _ <- ViewManager.update(design)
    } yield db,
    1.minute)

  def configure {
    bind(classOf[AuthProviders]).toInstance(authProviders)
  }
}
