package dit4c.gatehouse

import akka.actor.{Actor,ActorRefFactory}

import spray.util.LoggingContext
import spray.routing._
import spray.http._
import spray.json._
import MediaTypes._
import scala.collection.JavaConversions._

// this trait defines our service behavior independently from the service actor
class MiscService(implicit val actorRefFactory: ActorRefFactory)
    extends HttpService {

  val route =
    //logRequestResponse("") {
      path("") {
        get {
          respondWithMediaType(`text/html`) { // XML is marshalled to `text/xml` by default, so we simply override here
            complete {
              <html>
                <body>
                  <h1>DIT4C Gatehouse</h1>
                  <ul>
                    <a href="auth">Auth Query</a>
                  </ul>
                </body>
              </html>
            }
          }
        }
      } ~
      path("favicon.ico") {
        get {
          // serve up static content from a JAR resource
          getFromResource("dit4c/gatehouse/public/favicon.ico")
        }
      }
    //}
}

object MiscService {
  def route(implicit actorRefFactory: ActorRefFactory) =
    new MiscService().route
}
