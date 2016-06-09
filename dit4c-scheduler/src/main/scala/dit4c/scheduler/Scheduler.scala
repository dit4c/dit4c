package dit4c.scheduler

import dit4c.scheduler.utils.SchedulerConfig
import akka.http.scaladsl.Http
import dit4c.scheduler.routes._
import scala.concurrent.Future
import akka.http.scaladsl.Http.ServerBinding
import akka.actor.Props
import dit4c.scheduler.service.ClusterAggregateManager

object Scheduler {
  def apply(config: SchedulerConfig): Future[ServerBinding] = {
    (new Scheduler(config)).start
  }
}

protected class Scheduler(config: SchedulerConfig) extends utils.ActorModule {

  override def appName = config.name

  val zoneAggregateManager = system.actorOf(Props[ClusterAggregateManager])

  def handler =
    (new ClusterRoutes(zoneAggregateManager)).routes

  def start = {
    Http().bindAndHandle(
        handler,
        "localhost", config.port)
  }


}