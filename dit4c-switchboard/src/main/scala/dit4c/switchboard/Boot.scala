package dit4c.switchboard

import java.net.URI
import akka.actor._
import scala.concurrent.Future
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import org.fusesource.scalate._

object Boot extends App {
  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  implicit val upstreamReads: Reads[Upstream] = (
    (__ \ "scheme").read[String] and
    (__ \ "host").read[String] and
    (__ \ "port").read[Int]
  )(Upstream.apply _)

  implicit val routeReads: Reads[Route] = (
    (__ \ "domain").read[String] and
    (__ \ "headers").read[Map[String, String]] and
    (__ \ "upstream").read[Upstream]
  )(Route.apply _)

  new Thread(new Runnable() {
    def run = {
      while (System.in.read() != -1) { }
      System.exit(0)
    }
  }).start

  val engine = new TemplateEngine

  protected def replaceAllRoutes(routes: Seq[Route]) = routes.foreach { route =>
    println(route)
    try {
      val output = engine.layout("/nginx_vhost.tmpl.mustache",
        Map(
          "domain" -> route.domain,
          "headers" -> route.headers.map {
            case (k, v) => Map("name" -> k, "value" -> v)
          },
          "upstream" -> Map(
            "scheme" -> route.upstream.scheme,
            "host" -> route.upstream.host,
            "port" -> route.upstream.port.toString
          )
        )
      )
      println(output)
    } catch {
      case e => println(e)
    }
  }
  protected def setRoute(route: Route) = println(route)
  protected def deleteRoute(route: Route) = println(route)

  ArgParser.parse(args, Config()) map { config =>
    for {
      response <- Http().singleRequest(HttpRequest(uri = config.feed.toString))
    } yield {
      response.entity match {
        case HttpEntity.Chunked(mimeType, parts) =>
          parts
            .map(v => new String(v.data.decodeString(mimeType.charset.value)))
            .filter(_.startsWith("data: "))
            .map(_.replaceFirst("data: ", ""))
            .map(Json.parse(_))
            .runForeach { v =>
              println {(v \ "op").as[String] match {
                case "replace-all-routes" =>
                  replaceAllRoutes((v \ "routes").as[Seq[Route]])
                case "set-route" =>
                  setRoute((v \ "route").as[Route])
                case "delete-route" =>
                  deleteRoute((v \ "route").as[Route])
              }}
            }
      }
    }
  } getOrElse {
    // arguments are bad, error message will have been displayed
  }

}

case class Config(
  val feed: URI = new URI("https://example.test/routes"))

case class Route(
  domain: String,
  headers: Map[String, String],
  upstream: Upstream
)

case class Upstream(
  scheme: String,
  host: String,
  port: Int
)

object ArgParser extends scopt.OptionParser[Config]("dit4c-gatehouse") {
  help("help") text("prints this usage text")
  opt[URI]('f', "feed")
    .action { (x, c) => c.copy(feed = x) }
    .text("DIT4C Highcommand route feed")
}
