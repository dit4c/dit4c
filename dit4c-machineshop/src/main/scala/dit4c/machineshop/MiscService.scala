package dit4c.machineshop

import akka.actor.Actor
import akka.actor.ActorRefFactory
import spray.util.LoggingContext
import spray.routing._
import spray.http._
import spray.json._
import MediaTypes._
import scala.collection.JavaConversions._

class MiscService(arf: ActorRefFactory, serverId: String)
    extends HttpService with RouteProvider {

  implicit val actorRefFactory = arf

  val route: RequestContext => Unit =
    path("") {
      get {
        respondWithMediaType(`text/html`) { // XML is marshalled to `text/xml` by default, so we simply override here
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
      }
    } ~
    path("server-id") {
      respondWithMediaType(`text/plain`) {
        complete(serverId)
      }
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