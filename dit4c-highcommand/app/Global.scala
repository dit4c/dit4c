import providers.InjectorPlugin
import play.api._
import play.api.mvc._
import com.google.inject.Injector
import play.filters.gzip.GzipFilter

object Global extends WithFilters(new GzipFilter()) with GlobalSettings {

  def injector: Injector =
    Play.current.plugin(classOf[InjectorPlugin]).get.injector.get

  override def getControllerInstance[A](controllerClass: Class[A]): A =
    injector.getInstance(controllerClass)
}
