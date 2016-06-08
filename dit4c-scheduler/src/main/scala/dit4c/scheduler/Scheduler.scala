package dit4c.scheduler

import dit4c.scheduler.utils.SchedulerConfig

object Scheduler {

  def apply(config: SchedulerConfig) = {
    println(config.name, config.port)
    // TODO: Where the action happens
  }

}