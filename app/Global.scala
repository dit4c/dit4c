import scala.util._
import com.google.inject._
import play.api._
import providers.auth._
import play.api.templates.Html

object Global extends GlobalSettings {

  def injector = Guice.createInjector(new AbstractModule {
    def configure {
      val appConfig = Play.current.configuration
      val authProvider = appConfig.getConfig("rapidaaf").flatMap { c =>
        Try({
          val id = c.getString("id").getOrElse("RapidAAF")
          val url = new java.net.URL(c.getString("url").get)
          val key = c.getString("key").get
          val config = new RapidAAFAuthProviderConfig(id, url, key)
          new RapidAAFAuthProvider(config)
        }).toOption
      }.getOrElse {
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
      bind(classOf[AuthProvider]).toInstance(authProvider)
    }
  })

  override def getControllerInstance[A](controllerClass: Class[A]): A =
    injector.getInstance(controllerClass)
}
