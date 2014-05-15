package providers

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try
import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import play.api.Plugin
import play.api.templates.Html
import providers.auth.AuthProvider
import providers.auth.CallbackResult
import providers.auth.RapidAAFAuthProvider
import providers.auth.RapidAAFAuthProviderConfig
import providers.db.CouchDB
import providers.db.CouchDBPlugin
import providers.auth.Identity

class InjectorPlugin(app: play.api.Application) extends Plugin {

  implicit val ec = play.api.libs.concurrent.Execution.defaultContext

  var injector: Option[Injector] = None

  override def onStart {
    injector = Some(
      Guice.createInjector(new AbstractModule {
        val appConfig = app.configuration

        lazy val authProvider = appConfig.getConfig("rapidaaf").flatMap { c =>
          Try({
            val id = c.getString("id").getOrElse("RapidAAF")
            val url = new java.net.URL(c.getString("url").get)
            val key = c.getString("key").get
            val config = new RapidAAFAuthProviderConfig(id, url, key)
            new RapidAAFAuthProvider(config)
          }).toOption
        }.getOrElse {
          if (appConfig.getBoolean("dummyauth").getOrElse(false)) {
            new AuthProvider {
              val errorMsg = "AuthProvider not configured"
              override def callbackHandler = { _ =>
                CallbackResult.Success(new Identity {
                  override val uniqueId = "dummy:anonymous"
                  override val name = None
                  override val emailAddress = None
                })
              }
              override def loginButton = Html(
                s"""|<form class="form-inline" action="/auth/callback" method="post">
                    |  <button class="btn btn-primary" type="submit">Login</button>
                    |</form>
                    |""".stripMargin
              )
            }
          } else {
            new AuthProvider {
              val errorMsg = "AuthProvider not configured"
              override def callbackHandler = { _ =>
                CallbackResult.Failure(errorMsg)
              }
              override def loginButton = Html(
                s"""<div class="alert alert-danger">$errorMsg</div>"""
              )
            }
          }
        }

        val dbName = "dit4c-highcommand"

        lazy val dbServerInstance = app.plugin[CouchDBPlugin].get.get

        // Make sure a database exists
        lazy val database = Await.result(
          dbServerInstance.databases(dbName).flatMap {
            case Some(db) => Future.successful(db)
            case None => dbServerInstance.databases.create(dbName)
          },
          1.minute)

        def configure {
          bind(classOf[AuthProvider]).toInstance(authProvider)
          bind(classOf[CouchDB.Database]).toInstance(database)
        }
      })
    )
  }

  override def onStop {
    injector = None
  }

}