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

class ApiService(arf: ActorRefFactory, client: DockerClient) extends HttpService with RouteProvider {

  import scala.concurrent.ExecutionContext.Implicits.global
  import ApiService.marshallers._

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

  def apply(client: DockerClient)(implicit actorRefFactory: ActorRefFactory) =
    new ApiService(actorRefFactory, client)

  object marshallers {
    implicit val ContainerJsonMarshaller: ToResponseMarshaller[DockerContainer] =
      Marshaller.of[DockerContainer](ContentTypes.`application/json`) { (value, contentType, ctx) =>
        val text = jsonProtocol.ContainerWriter.write(value).compactPrint
        ctx.marshalTo(HttpEntity(contentType, text))
      }

    implicit val ContainersJsonMarshaller: ToResponseMarshaller[Seq[DockerContainer]] =
      Marshaller.of[Seq[DockerContainer]](ContentTypes.`application/json`) { (value, contentType, ctx) =>
        val text = jsonProtocol.ContainersWriter.write(value).compactPrint
        ctx.marshalTo(HttpEntity(contentType, text))
      }
  }

  object jsonProtocol extends DefaultJsonProtocol {
    implicit object ContainerWriter extends RootJsonWriter[DockerContainer] {
      def write(c: DockerContainer) = {
        JsObject(
          "name" -> JsString(c.name),
          "active" -> JsBoolean(c.isRunning)
        )
      }
    }

    implicit object ContainersWriter extends RootJsonWriter[Seq[DockerContainer]] {
      def write(cs: Seq[DockerContainer]) =
        JsArray(cs.map(ContainerWriter.write(_)).toSeq: _*)
    }
  }

}