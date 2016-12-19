package dit4c.scheduler.domain

import dit4c.scheduler.runner.RktRunner

trait ConfigProvider {

  def rktRunnerConfig: RktRunner.Config

}