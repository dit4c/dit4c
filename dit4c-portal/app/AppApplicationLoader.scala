import controllers.{Assets, MainController}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.routing.Router
import router.Routes
import com.softwaremill.macwire._

class AppApplicationLoader extends ApplicationLoader {
  def load(context: Context) = {
    (new AppComponents(context)).application
  }
}

class AppComponents(context: Context)
    extends BuiltInComponentsFromContext(context) {
  lazy val router: Router = {
    lazy val prefix = "/"
    wire[Routes]
  }
  lazy val mainController = wire[MainController]
  lazy val assetsController = wire[Assets]
}