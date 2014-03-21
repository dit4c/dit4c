package dit4c.gatehouse

import akka.actor.Actor

import spray.util.LoggingContext
import spray.routing._
import spray.http._
import spray.json._
import MediaTypes._
import com.kpelykh.docker.client.DockerClient
import scala.collection.JavaConversions._

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class MyServiceActor extends Actor with MyService {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  // this actor only runs our route, but you could add
  // other things here, like request stream processing
  // or timeout handling
  def receive = runRoute(myRoute)
}


// this trait defines our service behavior independently from the service actor
trait MyService extends HttpService {

  implicit class ProjectNameTester(str: String) {

    // Same as domain name, but use of capitals is prohibited because container
    // names are case-sensitive while host names should be case-insensitive.
    def isValidProjectName = {
      !str.isEmpty &&
      str.length <= 63 &&
      !str.startsWith("-") &&
      !str.endsWith("-") &&
      str.matches("[a-z0-9\\-]+")
    }

  }


  val myRoute =
    logRequestResponse("") {
      path("") {
        get {
          respondWithMediaType(`text/html`) { // XML is marshalled to `text/xml` by default, so we simply override here
            complete {
              <html>
              <body>
                <h1>DIT4C Gatehouse</h1>
                <ul>
                  <a href="projects">Project List</a>
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
      } ~
      path("projects") {
        get {
          detach() {
            respondWithMediaType(`application/json`) {
              complete {
                import DefaultJsonProtocol._
                val dockerClient = new DockerClient("http://localhost:4243");
                val containers = dockerClient.listContainers(false, false)
                containers.toSeq.map { container =>
                  container.getNames.map(_.stripPrefix("/"))
                }.flatten.filter(_.isValidProjectName).sorted.toJson.toString
              }
            }
          }
        }
      }
    }
}