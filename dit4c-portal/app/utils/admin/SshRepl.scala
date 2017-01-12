package utils.admin

import scala.concurrent.Future
import ammonite.ops.Path
import ammonite.ops.tmp.{dir => tmpdir}
import ammonite.sshd.SshServerConfig
import ammonite.sshd.SshdRepl
import javax.inject.Inject
import play.api.Configuration
import play.api.Environment
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import ammonite.util.Bind
import java.nio.file.Files

case class SshRepl @Inject() (
    lifecycle: ApplicationLifecycle,
    env: Environment,
    config: Configuration,
    bindings: Seq[Bind[_]],
    classloader: ClassLoader) {
  val logger = Logger(this.getClass)
  val homeDir = tmpdir()
  val sshServerConfig = {
    val name = "dit4c"
    val interface = config.getString("ssh.ip").getOrElse("localhost")
    val port = config.getInt("ssh.port").getOrElse(2222)
    val username = config.getString("ssh.user").getOrElse("dit4c")
    val password = config.getString("ssh.password")
        .orElse(config.getString("play.crypto.secret").filter(_ != "changeme"))
        .getOrElse("dit4c")
    val hostKeyFile = config.getString("ssh.hostKeyFile")
        .map(relPath => Path(env.getFile(relPath).toString))
        .getOrElse(homeDir./("ssh-host.key"))
    SshServerConfig(
      address = interface,
      port = port,
      username = username,
      password = password,
      ammoniteHome = homeDir,
      hostKeyFile = Some(hostKeyFile)
    )
  }
  val replServer = new SshdRepl(
      sshServerConfig,
      replArgs = bindings,
      classLoader = classloader)
  // Attach to lifecycle
  lifecycle.addStopHook { () =>
    Future.successful(replServer.stop())
  }
  // Automatically start
  replServer.start()
  logger.info(s"SSH admin REPL on ${sshServerConfig.address}:${replServer.port}")
}
