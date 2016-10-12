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
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Terminated
import akka.actor.ActorLogging
import akka.stream.ActorMaterializer
import scala.concurrent.Await
import scala.concurrent.duration._

object Scheduler {

  def apply(config: SchedulerConfig): Unit = {
    (new SchedulerEnvironment(config))()
  }

  protected class SchedulerEnvironment(config: SchedulerConfig) extends utils.ActorModule {

    override def appName = config.name

    def apply(): Future[Unit] = {
      import system.dispatcher
      system.actorOf(Props(classOf[Scheduler], config))
      system.whenTerminated.map(_ => ())
    }

  }

}

class Scheduler(config: SchedulerConfig) extends Actor with ActorLogging {
  import akka.http.scaladsl.server._

  var serverBinding: Option[ServerBinding] = null

  override def preStart {
    import context.dispatcher
    implicit val materializer = ActorMaterializer()(context.system)
    val clusterAggregateManager = context.actorOf(
        Props(classOf[ClusterAggregateManager], defaultConfigProvider),
        "cluster-aggregate-manager")
    val httpHandler = (new ClusterRoutes(clusterAggregateManager)).routes
    Http()(context.system).bindAndHandle(httpHandler, "localhost", config.port).foreach { sb =>
      serverBinding = Some(sb)
      log.info(s"Listening on ${sb.localAddress}")
    }
  }

  override def receive = {
    case _ => // TODO: Implement
  }

  override def postStop = {
    serverBinding.foreach { sb =>
      Await.result(sb.unbind(), 5.seconds)
    }
    super.postStop()
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

