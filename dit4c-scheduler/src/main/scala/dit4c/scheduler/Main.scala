package dit4c.scheduler

import scala.reflect.runtime.universe
import utils._

object Main extends App {

  import scala.concurrent.ExecutionContext.Implicits.global

  val appMetadata: AppMetadata = {
    val mirror = universe.runtimeMirror(getClass.getClassLoader)
    mirror.reflectModule(mirror.staticModule("dit4c.scheduler.AppMetadataImpl"))
        .instance.asInstanceOf[AppMetadata]
  }

  (new SchedulerConfigParser(appMetadata))
    .parse(args)
    .map { config =>
      Scheduler(config).foreach { sb =>
        println(s"Listening on ${sb.localAddress}")
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
}