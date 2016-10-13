package utils.admin

import scala.concurrent.Future

import ammonite.ops.Path
import ammonite.sshd.SshServerConfig
import ammonite.sshd.SshdRepl
import javax.inject.Inject
import play.api.Configuration
import play.api.Environment
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import ammonite.util.Bind

case class SshRepl @Inject() (
    lifecycle: ApplicationLifecycle,
    env: Environment,
    config: Configuration,
    bindings: Seq[Bind[_]]) {
  val logger = Logger(this.getClass)
  val sshServerConfig = {
    val name = "dit4c"
    val interface = config.getString("ssh.ip").getOrElse("localhost")
    val port = config.getInt("ssh.port").getOrElse(2222)
    val username = config.getString("ssh.user").getOrElse("dit4c")
    val password = config.getString("ssh.password").getOrElse("dit4c")
    val hostKeyFile = Path(env.getFile(config.getString("ssh.hostKeyFile").getOrElse("ssh-host.key")).toString)
    SshServerConfig(
      address = interface,
      port = port,
      username = username,
      password = password,
      hostKeyFile = Some(hostKeyFile)
    )
  }
  val replServer = new SshdRepl(sshServerConfig, replArgs = bindings)
  // Attach to lifecycle
  lifecycle.addStopHook { () =>
    Future.successful(replServer.stop())
  }
  // Automatically start
  replServer.start()
  logger.info(s"SSH admin REPL on ${sshServerConfig.address}:${replServer.port}")
}