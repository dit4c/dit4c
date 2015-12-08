package dit4c.switchboard.nginx

import org.specs2.mutable.Specification
import java.nio.file.Files
import scala.concurrent._
import scala.concurrent.duration._
import org.specs2.execute.Result
import org.specs2.matcher.PathMatchers
import scala.concurrent.ExecutionContext.Implicits
import dit4c.switchboard.Route

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

    "be able to set a new route" >> withNewInstance(55001) { instance =>
      val route = Route(
        "foo.example.test",
        Map("X-Foo" -> "bar"),
        Route.Upstream("https", "127.0.0.1", 443)
      )
      instance.setRoute(route)
      val vhostFile = instance.config.vhostDir.resolve(s"${route.domain}.conf")
      vhostFile.toString must beAnExistingPath
      val vhostContent = (new String(Files.readAllBytes(vhostFile), "utf-8"))

      vhostContent must contain("listen *:55001 ;")
      vhostContent must contain(s"server_name ${route.domain};")
      vhostContent must contain("proxy_pass https://127.0.0.1:443;")
      vhostContent must contain("proxy_set_header X-Forwarded-Proto $user_proto;")
      vhostContent must contain("proxy_set_header X-Forwarded-Host \"foo.example.test\";")
      vhostContent must contain("proxy_set_header X-Foo \"bar\";")
      vhostContent must contain("# vHost \"comment\"")

      instance.shutdown
      done
    }

    "be able to delete routes" >> withNewInstance(55002) { instance =>
      val route = Route(
        "foo.example.test",
        Map("X-Foo" -> "bar"),
        Route.Upstream("https", "127.0.0.1", 443)
      )
      instance.setRoute(route)
      val vhostFile = instance.config.vhostDir.resolve(s"${route.domain}.conf")
      vhostFile.toString must beAnExistingPath
      instance.deleteRoute(route)
      vhostFile.toString must not(beAnExistingPath)

      instance.shutdown
      done
    }

  }

  def withNewInstance(port: Int)(op: NginxInstance => Result) = {
    val nginxInstance = new NginxInstance(Some("example.test"), port,
        None, Some("# Main comment"), Some("# vHost \"comment\""))
    try {
      op(nginxInstance)
    } catch {
      case e: Throwable =>
        nginxInstance.nginxProcess.destroy
        throw e
    }
  }

}