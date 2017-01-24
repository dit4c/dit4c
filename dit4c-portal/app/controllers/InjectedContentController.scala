package controllers

import com.softwaremill.tagging._
import play.api.Configuration
import play.api.mvc.Controller
import play.api.i18n.I18nSupport
import play.api.mvc.Action
import net.ceedubs.ficus.Ficus._
import play.api.libs.ws.WSClient
import scala.concurrent._
import scala.concurrent.duration._
import play.api.cache.CacheApi

class InjectedContentController(
    cache: CacheApi @@ InjectedContentController,
    config: Configuration,
    wsClient: WSClient)(implicit ec: ExecutionContext)
    extends Controller {

  val log = play.api.Logger(this.getClass)

  val loginMessageCacheKey = "login.message"
  lazy val loginMessageCacheTTL =
    config.underlying.as[Int]("login.message.ttl").seconds

  def loginBackgroundImage = Action {
    Redirect(config.underlying.as[String]("login.background-image-url"))
  }

  def loginMarkdownMessage = Action.async {
    cache.get[String](loginMessageCacheKey)
      .map(Future.successful)
      .getOrElse {
        Future(config.underlying.as[String]("login.message.url"))
          .flatMap(url => wsClient.url(url).get())
          .map { response =>
            val msg = response.body
            cache.set(loginMessageCacheKey, msg, loginMessageCacheTTL)
            msg
          }
      }
      .map(msg => Ok(msg))
      .recover {
        case _ => Ok(config.underlying.as[String]("login.message.text"))
      }
  }

}