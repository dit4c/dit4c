package dit4c.machineshop

import akka.actor.Actor
import akka.actor.ActorRefFactory
import spray.util.LoggingContext
import spray.routing._
import spray.http._
import spray.json._
import MediaTypes._
import scala.collection.JavaConversions._
import dit4c.machineshop.docker.DockerClient
import dit4c.machineshop.docker.models.DockerContainer
import scala.util._
import spray.httpx.marshalling.Marshaller
import spray.httpx.marshalling.ToResponseMarshaller
import spray.httpx.unmarshalling.Unmarshaller
import spray.httpx.unmarshalling.FromRequestUnmarshaller
import spray.httpx.SprayJsonSupport
import spray.httpx.unmarshalling.UnmarshallerLifting

class ApiService(arf: ActorRefFactory, client: DockerClient) extends HttpService with RouteProvider {

  import scala.concurrent.ExecutionContext.Implicits.global
  import ApiService.NewProjectRequest
  import ApiService.marshallers._
  import ApiService.marshallers.newProjectRequestUnmarshaller

  implicit val actorRefFactory = arf

  val route: RequestContext => Unit =
    path("projects") {
      get {
        respondWithMediaType(`application/json`) {
          onSuccess(client.containers.list) { containers =>
            complete {
              containers
            }
          }
        }
      }
    } ~
    path("projects" / "new") {
      post {
        entity(as[NewProjectRequest]) { npr =>
          respondWithMediaType(`application/json`) {
            onSuccess(client.containers.create(npr.name)) { container =>
              complete(container)
            }
          }
        }
      }
    } ~
    path("projects" / "\\w+".r) { (name: String) =>
      get {
        respondWithMediaType(`application/json`) {
          onSuccess(client.containers.list) { containers =>
            containers.find(c => c.name == name) match {
              case Some(c) => complete(c)
              case None => complete(StatusCodes.NotFound)
            }
          }
        }
      }
    }
}

object ApiService {

  case class NewProjectRequest(val name: String)

  def apply(client: DockerClient)(implicit actorRefFactory: ActorRefFactory) =
    new ApiService(actorRefFactory, client)

  object marshallers extends DefaultJsonProtocol with SprayJsonSupport with UnmarshallerLifting {
    implicit val newProjectRequestReader = jsonFormat1(NewProjectRequest)

    implicit val containerWriter = new RootJsonWriter[DockerContainer] {
      def write(c: DockerContainer) = {
        JsObject(
          "name" -> JsString(c.name),
          "active" -> JsBoolean(c.isRunning)
        )
      }
    }

    implicit val containersWriter = new RootJsonWriter[Seq[DockerContainer]] {
      def write(cs: Seq[DockerContainer]) =
        JsArray(cs.map(containerWriter.write(_)).toSeq: _*)
    }

    implicit val newProjectRequestUnmarshaller: FromRequestUnmarshaller[NewProjectRequest] =
      fromRequestUnmarshaller(fromMessageUnmarshaller(sprayJsonUnmarshaller(newProjectRequestReader)))

    implicit val containerJsonMarshaller: ToResponseMarshaller[DockerContainer] =
      sprayJsonMarshaller(containerWriter)

    implicit val containersJsonMarshaller: ToResponseMarshaller[Seq[DockerContainer]] =
      sprayJsonMarshaller(containersWriter)

  }

}