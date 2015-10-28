package dit4c.gatehouse

import akka.actor.{Actor,ActorRefFactory}

import spray.json._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.server.Directives._
import scala.collection.JavaConversions._
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._

// this trait defines our service behavior independently from the service actor
class MiscService(implicit val actorRefFactory: ActorRefFactory) {

  val route =
    //logRequestResponse("") {
      path("") {
        get {
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
