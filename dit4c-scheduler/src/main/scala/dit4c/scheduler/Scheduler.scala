package dit4c.scheduler

import dit4c.scheduler.utils.SchedulerConfig
import akka.http.scaladsl.Http
import dit4c.scheduler.routes._
import scala.concurrent.Future
import akka.http.scaladsl.Http.ServerBinding

object Scheduler {
  def apply(config: SchedulerConfig): Future[ServerBinding] = {
    (new Scheduler(config)).start
  }
}

protected class Scheduler(config: SchedulerConfig) extends utils.ActorModule {

  override def appName = config.name

  def handler =
    apiDocsRoutes(system, "localhost:"+config.port) ~
    (new ZoneRoutes()).routes

  def start = {
    Http().bindAndHandle(
        handler,
        "localhost", config.port)
  }


}