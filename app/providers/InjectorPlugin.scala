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
import providers.auth.AuthProvider
import providers.auth.CallbackResult
import providers.auth.RapidAAFAuthProvider
import providers.auth.RapidAAFAuthProviderConfig
import providers.db.CouchDB
import providers.db.CouchDBPlugin
import providers.auth.Identity
import scala.concurrent.ExecutionContext
import models.ComputeNodeProjectHelper
import models.ComputeNodeProjectHelperImpl

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
              override def callbackHandler = { request =>
                request.body.asFormUrlEncoded.map { params =>
                  val username: String =
                    params.get("username").map(_.head).getOrElse("anonymous")
                  CallbackResult.Success(new Identity {
                    override val uniqueId = "dummy:"+username
                    override val name = Some(username)
                    override val emailAddress = None
                  })
                }.getOrElse {
                  CallbackResult.Failure("Form not posted.")
                }
              }
              override def loginURL = ??? // Should never be called
              override def loginButton = _ => Html(
                s"""|<form class="form-inline" action="/auth/callback" method="post">
                    |  <input class="form-control" type="text"
                    |         name="username" value="anonymous"/>
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
              override def loginURL = ???
              override def loginButton = _ => Html(
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
          bind(classOf[ExecutionContext]).toInstance(
              play.api.libs.concurrent.Execution.defaultContext)
          bind(classOf[ComputeNodeProjectHelper])
            .to(classOf[ComputeNodeProjectHelperImpl])
        }
      })
    )
  }

  override def onStop {
    injector = None
  }

}