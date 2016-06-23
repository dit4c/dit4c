package domain

import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import scala.concurrent.Future
import akka.util.ByteString
import akka.stream.ActorMaterializer
import akka.stream.Materializer

object ClusterAggregate {

  val defaultClusterProps = Props(classOf[ClusterAggregate],
      "default",
      Uri("http://localhost:8080/clusters/default"))

  sealed trait Command
  case class StartInstance(image: String, callback: Uri) extends Command
  case class GetInstanceStatus(instanceId: String) extends Command

  sealed trait Response
  case class InstanceStarted(clusterId: String, instanceId: String) extends Response
  case class InstanceStatus(httpResponse: HttpResponse) extends Response
  case object UnableToStartInstance extends Response


}

class ClusterAggregate(
    clusterId: String, baseUri: Uri) extends Actor with ActorLogging {
  import ClusterAggregate._
  import play.api.libs.json._
  import akka.pattern.pipe

  implicit val m: Materializer = ActorMaterializer()
  import context.dispatcher

  val http = Http(context.system)

  val receive: Receive = {
    case StartInstance(image, callback) =>
      startInstance(image, callback) pipeTo sender
    case GetInstanceStatus(instanceId) =>
      getInstanceStatus(instanceId) pipeTo sender
  }

  def startInstance(image: String, callback: Uri): Future[Response] = {
    val path = baseUri.withPath(baseUri.path / "instances")
    val reqJson = Json.obj(
        "image" -> image,
        "callback" -> callback.toString
    )
    val request = HttpRequest(
        method = HttpMethods.POST,
        uri = path,
        entity = HttpEntity.Strict(
            ContentTypes.`application/json`,
            ByteString.fromString(Json.prettyPrint(reqJson))))

    http.singleRequest(request).collect {
      case HttpResponse(StatusCodes.Accepted, headers, _, _) =>
        log.info("Request accepted")
        val location = Uri(headers.find(_.lowercaseName == "location").get.value)
        InstanceStarted(clusterId, location.path.last.toString)
    }.recover {
      case e: Throwable =>
        log.error("Unable to start instance: "+e.getMessage)
        UnableToStartInstance
    }
  }

  def getInstanceStatus(instanceId: String): Future[Response] = {
    val path = baseUri.withPath(baseUri.path / "instances" / instanceId)
    val request = HttpRequest(method = HttpMethods.GET, uri = path)
    http.singleRequest(request).map(InstanceStatus(_))
  }


  implicit class UriPathHelper(path: Uri.Path) {
    def last = path.reverse.head
  }

}