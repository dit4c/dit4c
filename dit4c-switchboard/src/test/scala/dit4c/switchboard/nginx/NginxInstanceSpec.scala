package dit4c.switchboard.nginx

import org.specs2.mutable.Specification
import java.nio.file.Files
import scala.concurrent._
import scala.concurrent.duration._
import org.specs2.execute.Result
import org.specs2.matcher.PathMatchers
import scala.concurrent.ExecutionContext.Implicits
import dit4c.switchboard.Route
import java.net.InetSocketAddress

class NginxInstanceSpec extends Specification with PathMatchers {

  import scala.concurrent.ExecutionContext.Implicits.global

  "NginxInstance should" >> {

    "start and shutdown" >> withNewInstance(55000) { instance =>
      val fExitValue = Future {
        instance.nginxProcess.exitValue
      }
      fExitValue.isCompleted must beFalse
      instance.config.baseDir.toString must beAnExistingPath
      instance.shutdown
      Await.result(fExitValue, 2 seconds) must_== 0
      instance.config.baseDir.toString must not(beAnExistingPath)
    }

  }

  def withNewInstance(port: Int)(op: NginxInstance => Result) = {
    val nginxInstance = new NginxInstance(port,
        None, Some("# Main comment"),
        InetSocketAddress.createUnresolved("127.0.0.1", 8080))
    try {
      op(nginxInstance)
    } catch {
      case e: Throwable =>
        nginxInstance.nginxProcess.destroy
        throw e
    }
  }

}