package dit4c.switchboard.nginx

import scala.collection.JavaConversions._
import scala.sys.process.Process
import scala.util._
import com.typesafe.scalalogging.LazyLogging
import scala.sys.process.ProcessLogger
import dit4c.switchboard.Route
import dit4c.switchboard.TlsConfig
import java.net.InetSocketAddress

class NginxInstance(
    port: Int,
    tlsConfig: Option[TlsConfig],
    extraMainConfig: Option[String],
    authSocket: InetSocketAddress) extends LazyLogging {

  val config = NginxConfig(port, tlsConfig, extraMainConfig, authSocket)

  val nginxPath: String =
    try {
      Process("which nginx").!!.stripLineEnd
    } catch {
      case e: Throwable =>
        throw new Exception("Nginx binary not found in PATH")
    }
  protected def pLog = ProcessLogger(logger.debug(_), logger.debug(_))
  val nginxProcess: Process =
    Process(s"$nginxPath -c ${config.mainConfig}").run(pLog)

  def shutdown = {
    nginxProcess.destroy
    config.cleanup
  }

  // Guard against instance not being shutdown, as there's no valid case where
  // it should be left running.
  sys.addShutdownHook(shutdown)

  protected def reloadAfter(f: => Unit) {
    f
    val p = Process(s"$nginxPath -c ${config.mainConfig} -s reload").run(pLog)
    if (p.exitValue != 0) {
      throw new RuntimeException(s"Reload failed. Exit code: ${p.exitValue}")
    }
  }

}
