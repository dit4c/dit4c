package dit4c.scheduler

import scala.reflect.runtime.universe
import utils._

object Main extends App {

  import scala.concurrent.ExecutionContext.Implicits.global

  val appMetadata: AppMetadata = {
    val mirror = universe.runtimeMirror(getClass.getClassLoader)
    new AppMetadata() {
      override def name = dit4c.common.BuildInfo.name
      override def version = dit4c.common.BuildInfo.version
    }
  }

  (new SchedulerConfigParser(appMetadata))
    .parse(args)
    .map { config =>
      Scheduler(config)
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
}