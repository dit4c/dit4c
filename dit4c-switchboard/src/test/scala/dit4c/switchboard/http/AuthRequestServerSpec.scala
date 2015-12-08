package dit4c.switchboard.http

import org.specs2.mutable.Specification
import java.nio.file.Files
import scala.concurrent._
import scala.concurrent.duration._
import org.specs2.execute.Result
import org.specs2.matcher.PathMatchers
import scala.concurrent.ExecutionContext.Implicits
import dit4c.switchboard.Route
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import java.net.InetSocketAddress
import akka.stream.ActorMaterializer
import akka.agent.Agent
import akka.http.scaladsl.model.headers.CustomHeader
import akka.stream.scaladsl.Source
import akka.http.ClientConnectionSettings
import akka.http.scaladsl.model.headers._

class AuthRequestServerSpec extends Specification with PathMatchers {

  import scala.concurrent.ExecutionContext.Implicits.global
  import dit4c.common.AkkaHttpExtras._

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  "AuthRequestServer should" >> {

    "should expose bound URI" >> {
      val routes = Set(
        Route("foobar.test", Map("X-Server-Name" -> "bar"),
            Route.Upstream("https","target.test", 8080)),
        Route("foobaz.test", Map("X-Server-Name" -> "baz"),
            Route.Upstream("http","target.baz", 8090))
      )
      val serverInstance = Await.result(AuthRequestServer.start(
          Agent(routes.map(v => (v.domain,v)).toMap)
      ), 5.seconds)

      val req = HttpRequest().withHeaders(Host(Uri.Host("foobar.test")))
      val res = Await.result({
        val c = Http().outgoingConnection(
          serverInstance.socket.getAddress,
          serverInstance.socket.getPort,
          None, ClientConnectionSettings(system), system.log)
        val p = Promise[HttpResponse]()
        Source.single(req).via(c)
          .runForeach((r) => p.trySuccess(r))
          .onFailure({ case e: Throwable => p.tryFailure(e) })
        p.future
      }, 1.second)

      res.status must_== StatusCodes.OK
      res.headers must contain(RawHeader("X-Target-Upstream",
          "https://target.test:8080"))

      Await.ready(serverInstance.shutdown(), 5.seconds)
      done
    }
  }
}