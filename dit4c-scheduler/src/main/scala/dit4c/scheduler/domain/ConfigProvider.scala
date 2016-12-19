package dit4c.scheduler.domain

import dit4c.scheduler.runner.RktRunner
import dit4c.scheduler.ssh.RemoteShell
import scala.concurrent.Future

trait ConfigProvider {

  def rktRunnerConfig: RktRunner.Config
  def sshKeys: Future[List[RemoteShell.OpenSshKeyPair]]

}