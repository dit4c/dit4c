package controllers

import play.api.mvc.Controller
import play.api.mvc.Action
import play.api.Configuration
import com.typesafe.config._
import play.api.libs.json._

class PublicConfigController(fullConfig: Configuration) extends Controller {

  val config = fullConfig.getConfig("public-config").getOrElse(Configuration.empty)

  def getConfigJson = Action {
    import com.typesafe.config.ConfigValueType._
    val json = Json.parse(
      config.underlying.root.render(
          ConfigRenderOptions.concise()))
    Ok(json)
  }

}