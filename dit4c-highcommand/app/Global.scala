import providers.InjectorPlugin
import play.api._
import com.google.inject.Injector

object Global extends GlobalSettings {

  def injector: Injector =
    Play.current.plugin(classOf[InjectorPlugin]).get.injector.get

  override def getControllerInstance[A](controllerClass: Class[A]): A =
    injector.getInstance(controllerClass)
}
