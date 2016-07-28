package dit4c.scheduler

import dit4c.scheduler.utils.SchedulerConfig
import akka.http.scaladsl.Http
import dit4c.scheduler.routes._
import scala.concurrent.Future
import akka.http.scaladsl.Http.ServerBinding
import akka.actor.Props
import dit4c.scheduler.service.ClusterAggregateManager
import dit4c.scheduler.domain.DefaultConfigProvider
import dit4c.scheduler.runner.RktRunner
import java.nio.file.Paths

object Scheduler {
  def apply(config: SchedulerConfig): Future[ServerBinding] = {
    (new Scheduler(config)).start
  }
}

protected class Scheduler(config: SchedulerConfig) extends utils.ActorModule {

  override def appName = config.name

  lazy val clusterAggregateManager = system.actorOf(
      Props(classOf[ClusterAggregateManager], defaultConfigProvider),
      "cluster-aggregate-manager")

  def handler =
    (new ClusterRoutes(clusterAggregateManager)).routes

  def start = {
    Http().bindAndHandle(
        handler,
        "localhost", config.port)
  }

  private val defaultConfigProvider: DefaultConfigProvider = new DefaultConfigProvider {
    override def rktRunnerConfig =
      RktRunner.Config(
          Paths.get("/var/lib/dit4c-rkt"),
          "dit4c-instance-",
          config.authImage,
          config.listenerImage)
  }

}