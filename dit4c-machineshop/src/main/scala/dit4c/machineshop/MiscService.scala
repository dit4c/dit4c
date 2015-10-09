package dit4c.machineshop

import akka.actor.Actor
import akka.actor.ActorRefFactory
import spray.json._
import akka.http.scaladsl.server.Directives._
import scala.collection.JavaConversions._
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import scala.concurrent.Future
import akka.http.scaladsl.server.RouteResult

class MiscService(arf: ActorRefFactory, serverId: String)
    extends RouteProvider {

  implicit val actorRefFactory = arf

  val route: RequestContext => Future[RouteResult] =
    path("") {
      get {
        complete {
          <html>
            <body>
              <h1>DIT4C MachineShop ({serverId})</h1>
              <ul>
                <li><a href="/containers">/containers</a></li>
              </ul>
            </body>
          </html>
        }
      }
    } ~
    path("server-id") {
      complete(serverId)
    } ~
    path("favicon.ico") {
      get {
        // serve up static content from a JAR resource
        getFromResource("dit4c/machineshop/public/favicon.ico")
      }
    }

}

object MiscService {

  def apply(serverId: String)(implicit actorRefFactory: ActorRefFactory) =
    new MiscService(actorRefFactory, serverId)

}