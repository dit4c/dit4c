package controllers

import play.api.Configuration
import play.api.mvc.Controller
import play.api.i18n.I18nSupport
import play.api.mvc.Action
import net.ceedubs.ficus.Ficus._

class InjectedContentController(
    config: Configuration)
    extends Controller {

  val log = play.api.Logger(this.getClass)

  def loginBackgroundImage = Action {
    Redirect(config.underlying.as[String]("login.background-image-url"))
  }

}