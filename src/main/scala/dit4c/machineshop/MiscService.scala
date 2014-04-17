package dit4c.machineshop

import akka.actor.Actor

import spray.util.LoggingContext
import spray.routing._
import spray.http._
import spray.json._
import MediaTypes._
import scala.collection.JavaConversions._

// this trait defines our service behavior independently from the service actor
trait MiscService extends HttpService {

  val miscRoute =
    //logRequestResponse("") {
      path("") {
        get {
          respondWithMediaType(`text/html`) { // XML is marshalled to `text/xml` by default, so we simply override here
            complete {
              <html>
                <body>
                  <h1>DIT4C MachineShop</h1>
                  <ul>
                    <li></li>
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
          getFromResource("dit4c/machineshop/public/favicon.ico")
        }
      }
    //}
}