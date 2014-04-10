import com.google.inject._
import play.api.GlobalSettings

object Global extends GlobalSettings {

  val injector = Guice.createInjector(new AbstractModule {
    def configure {}
  })

  override def getControllerInstance[A](controllerClass: Class[A]): A =
    injector.getInstance(controllerClass)
}
