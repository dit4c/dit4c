package dit4c.switchboard

import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.http.scaladsl.model.Uri

case class Route(
  domain: String,
  headers: Map[String, String],
  upstream: Route.Upstream
)

object Route {
  case class Upstream(
    scheme: String,
    host: String,
    port: Int
  ) {
    override val toString =
      Uri./.withScheme(scheme)
        .withAuthority(host, port)
        .withPath(Uri.Path.Empty)
        .toString
  }

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
}