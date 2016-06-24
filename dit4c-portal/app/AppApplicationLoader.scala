import controllers.{Assets, MainController}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.i18n._
import play.api.routing.Router
import router.Routes
import com.softwaremill.macwire._
import com.softwaremill.tagging._
import akka.actor.ActorSystem
import services._
import akka.actor.Props

class AppApplicationLoader extends ApplicationLoader {
  def load(context: Context) = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }
    (new AppComponents(context)).application
  }
}

class AppComponents(context: Context)
    extends BuiltInComponentsFromContext(context) {
  lazy val router: Router = {
    lazy val prefix = "/"
    wire[Routes]
  }
  lazy val langs: Langs = wire[DefaultLangs]
  lazy val messsages: MessagesApi = wire[DefaultMessagesApi]
  val clusterAggregateManager = actorSystem.actorOf(
      Props(classOf[ClusterAggregateManager]))
      .taggedWith[ClusterAggregateManager]
  val instanceAggregateManager = actorSystem.actorOf(
      Props(classOf[InstanceAggregateManager], clusterAggregateManager))
      .taggedWith[InstanceAggregateManager]
  val userAggregateManager = actorSystem.actorOf(
      Props(classOf[UserAggregateManager], instanceAggregateManager))
      .taggedWith[UserAggregateManager]
  lazy val mainController = wire[MainController]
  lazy val assetsController = wire[Assets]
}