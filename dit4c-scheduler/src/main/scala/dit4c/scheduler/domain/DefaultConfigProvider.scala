package dit4c.scheduler.domain

import dit4c.scheduler.runner.RktRunner

trait DefaultConfigProvider {

  def rktRunnerConfig: RktRunner.Config

}