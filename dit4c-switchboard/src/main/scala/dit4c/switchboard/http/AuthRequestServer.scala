package dit4c.switchboard.http

import akka.http.scaladsl.Http.ServerBinding
import scala.concurrent.Future
import akka.http.scaladsl.Http
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import java.net.InetSocketAddress
import scala.util.matching.Regex
import akka.agent.Agent
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import scala.collection.immutable.{Seq => ISeq}

object AuthRequestServer {

  case class Instance(socket: InetSocketAddress, shutdown: () => Future[Unit])

  def start(
      routes: Agent[Option[String => Option[dit4c.switchboard.Route]]],
      interface: String = "localhost",
      port: Int = 0)(implicit system: ActorSystem): Future[Instance] = {
    implicit val materializer = ActorMaterializer()
    implicit val ec = system.dispatcher

    def routeHeaders(route: dit4c.switchboard.Route): ISeq[HttpHeader] =
      route.headers.toSeq.map({ case (k,v) =>
        RawHeader(k,v).asInstanceOf[HttpHeader]
      }).toIndexedSeq :+ RawHeader("X-Target-Upstream", route.upstream.toString)

    val handler = {
      import akka.http.scaladsl.server.Directives._
      host(".*".r) { host =>
        routes.get.map {
          _(host) match {
            case Some(route) =>
              complete(HttpResponse(headers = routeHeaders(route)))
            case None =>
              complete(HttpResponse(StatusCodes.NotFound))
          }
        }.getOrElse {
          complete(HttpResponse(StatusCodes.ServiceUnavailable))
        }
      }
    }

    // Bind to port
    Http().bindAndHandle(handler, interface, port).map { binding =>
      Instance(binding.localAddress , () => binding.unbind)
    }
  }
}