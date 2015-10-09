package dit4c.machineshop.docker

import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor._
import akka.io.IO
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.event.Logging
import akka.http.scaladsl.model.Uri
import dit4c.machineshop.docker.models._
import java.util.{Calendar, TimeZone}
import com.typesafe.config.ConfigFactory
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http
import akka.stream.Materializer
import spray.json.JsValue
import spray.json._
import spray.json.DefaultJsonProtocol._
import akka.event.LoggingAdapter
import akka.http.scaladsl.client.TransformerAux._
import akka.stream.ActorMaterializer
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import scala.collection.JavaConversions._
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import com.github.dockerjava.core.command.PullImageResultCallback

class DockerClientImpl(
    val baseUrl: Uri,
    val newContainerLinks: Seq[ContainerLink] = Nil) extends DockerClient {

  implicit val system: ActorSystem = ActorSystem()
  implicit val mat: Materializer = ActorMaterializer()
  implicit val timeout: Timeout = Timeout(15.seconds)
  import system.dispatcher // implicit execution context
  val log: LoggingAdapter = Logging(system, getClass)

  // Uncapped ExecutionContext, so we can do sync network requests
  val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)

  val docker: com.github.dockerjava.api.DockerClient = {
    val config =
      DockerClientConfig.createDefaultConfigBuilder
        .withUri(baseUrl.toString)
        .build
    DockerClientBuilder.getInstance(config).build
  }

  // Overridden in unit tests
  def sendAndReceive: HttpRequest => Future[HttpResponse] =
    Http().singleRequest(_)

  override val images = ImagesImpl
  override val containers = ContainersImpl

  case class ImageImpl(
      val id: String,
      val names: Set[String],
      val created: Calendar) extends DockerImage

  object ImagesImpl extends DockerImages {

    def list = Future({
      docker.listImagesCmd.exec.toIterable.map { img =>
        ImageImpl(
            img.getId,
            img.getRepoTags.toSet.filter(_ != "<none>:<none>"),
            {
              val c = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
              c.setTimeInMillis(img.getCreated * 1000)
              c
            })
      }.filter(!_.names.isEmpty).toSeq
    })(ec)

    def pull(imageName: String, tagName: String) = Future({
      docker.pullImageCmd(imageName).withTag(tagName)
        .exec(new PullImageResultCallback()).awaitSuccess
    })(ec)

  }



  class ContainerImpl(val id: String, val name: String, val status: ContainerStatus) extends DockerContainer {

    override def refresh = {

      def parseJsonResponse: JsValue => DockerContainer = { json =>
        val obj = json.asJsObject

        val status =
          if (obj.fields("State").asJsObject.fields("Running").convertTo[Boolean])
            ContainerStatus.Running
          else
            ContainerStatus.Stopped
        new ContainerImpl(id, name, status)
      }

      val pipeline: HttpRequest => Future[DockerContainer] =
        sendAndReceive ~>[HttpResponse,HttpResponse,Future[HttpResponse]] logValue(log, Logging.DebugLevel) ~>[HttpResponse,Future[JsValue],Future[JsValue]] reqToJson ~> parseJsonResponse

      pipeline {
        Get(baseUrl + s"containers/$id/json")
      }

    }

    override def start = {

      def parseResponse: HttpResponse => Unit = { res =>
        if (res.status == StatusCodes.NotFound) {
          throw new Exception("Container does not exist")
        }
      }

      val pipeline: HttpRequest => Future[Unit] =
        sendAndReceive ~>[HttpResponse,HttpResponse,Future[HttpResponse]] logValue(log, Logging.DebugLevel) ~> parseResponse

      val startRequest =
        JsObject(
          "PortBindings" -> JsObject(),
          "PublishAllPorts" -> JsTrue
        )

      pipeline({
        Post(baseUrl + s"containers/$id/start",
          HttpEntity(ContentTypes.`application/json`, startRequest.compactPrint))
      }).flatMap({
        case _: Unit => this.refresh
      })
    }

    override def stop(timeout: Duration) = {

      def parseResponse: HttpResponse => Unit = { res =>
        if (res.status == StatusCodes.NotFound) {
          throw new Exception("Container does not exist")
        }
      }

      val pipeline: HttpRequest => Future[Unit] =
        sendAndReceive ~>[HttpResponse,HttpResponse,Future[HttpResponse]] logValue(log, Logging.DebugLevel) ~> parseResponse

      // Cannot have negative timeout
      val t = Math.max(0, timeout.toSeconds)

      pipeline({
        Post(baseUrl + s"containers/$id/stop?t=$t")
      }).flatMap({
        case _: Unit => this.refresh
      })
    }

    /*
    override def export(sendChunk: HttpMessagePart => Future[Unit]): Unit = {
      type ChunkFuture = () => Future[HttpMessagePart]

      import java.io._
      import com.ning.http.client._
      import BodyDeferringAsyncHandler.BodyDeferringInputStream
      import AsyncHandler.STATE


      val maxChunkSize = 1024
      val pout = new PipedOutputStream
      val handler = new AsyncHandler[Unit] {
        override def onStatusReceived(s: HttpResponseStatus): STATE = {
          s.getStatusCode match {
            case 200 =>
              STATE.CONTINUE
            case other =>
              blockingSend(ChunkedResponseStart(HttpResponse(other)))
              blockingSend(ChunkedMessageEnd)
              STATE.ABORT
          }
        }
        def onHeadersReceived(headers: HttpResponseHeaders): STATE = {
          blockingSend(ChunkedResponseStart(HttpResponse(200)))
          STATE.CONTINUE
        }
        def onBodyPartReceived(bodyPart: HttpResponseBodyPart): STATE = {
          if (blockingSend(MessageChunk(bodyPart.getBodyPartBytes)))
            STATE.CONTINUE
          else
            STATE.ABORT
        }
        def onCompleted(): Unit = {
          blockingSend(ChunkedMessageEnd)
        }
        def onThrowable(t: Throwable): Unit = {
          log.error(t, "Prematurely ending export")
          // Don't send end message, so client doesn't think it has everything
        }
Response
        private def blockingSend(chunk: HttpMessagePart): Boolean = {
          import scala.concurrent.Await
          try {
            Await.ready(sendChunk(chunk), Duration("10 seconds"))
            true
          } catch {
            case e: java.util.concurrent.TimeoutException =>
              log.error(e, "client send failed");
              false
          }
        }
      }

      for {
        _ <- (new dispatch.Http)(
            dispatch.url(baseUrl + s"containers/$id/export").GET.toRequest,
            handler)
      } yield ()
    }
    */

    override def delete = {

      def parseResponse: HttpResponse => Unit = { res =>
        res.status match {
          case StatusCodes.NotFound =>
            throw new Exception("Container does not exist")
          case StatusCodes.InternalServerError =>
            throw new Exception(
                "Deletion failed due to server error.")
          case _: StatusCode => Unit
        }
      }

      val pipeline: HttpRequest => Future[Unit] =
        sendAndReceive ~>[HttpResponse,HttpResponse,Future[HttpResponse]] logValue(log, Logging.DebugLevel) ~> parseResponse

      pipeline({
        Delete(baseUrl + s"containers/$id?v=1")
      })
    }

  }

  object ContainersImpl extends DockerContainers {

    override def create(name: String, image: DockerImage) = {

      def parseJsonResponse: JsValue => DockerContainer = { json =>

        val obj = json.asJsObject

        new ContainerImpl(obj.getFields("Id").head.convertTo[String], name,
            ContainerStatus.Stopped)
      }

      val pipeline: HttpRequest => Future[DockerContainer] =
        sendAndReceive ~>[HttpResponse,HttpResponse,Future[HttpResponse]] logValue(log, Logging.DebugLevel) ~>[HttpResponse,Future[JsValue],Future[JsValue]] reqToJson ~> parseJsonResponse

      val createRequest =
        JsObject(
          "Hostname" -> JsString(name),
          "Tty" -> JsTrue,
          "AttachStdout" -> JsTrue,
          "AttachStderr" -> JsTrue,
          "CpuShares" -> JsNumber(1),
          "Image" -> JsString(image),
          "HostConfig" -> JsObject(
            "Links" -> newContainerLinks.map(_.toString.toJson).toJson,
            "RestartPolicy" -> JsObject(
              "Name" -> JsString("always")
            )
          )
        )

      pipeline {
        if (!name.isValidContainerName) {
          throw new IllegalArgumentException(
              "Name must be a valid lower-case DNS label")
        }
        Post(baseUrl + s"containers/create?name=$name",
          HttpEntity(ContentTypes.`application/json`, createRequest.compactPrint))
      }
    }

    override def list = {

      def parseJsonResponse: JsValue => Seq[DockerContainer] = { json =>
        json.convertTo[Seq[JsObject]]
          .map { obj: JsObject =>
            val Seq(jsId, namesWithSlashes, jsStatus) =
              obj.getFields("Id", "Names", "Status")
            // Get the first name, without the slash
            // (Multiple names are possible, but first should be native name.)
            val name: String = namesWithSlashes.convertTo[List[String]] match {
              case nameWithSlash :: _ if nameWithSlash.startsWith("/") =>
                nameWithSlash.stripPrefix("/")
            }
            val status =
              if (jsStatus.convertTo[String].matches("Up .*"))
                ContainerStatus.Running
              else
                ContainerStatus.Stopped
            new ContainerImpl(jsId.convertTo[String], name, status)
          }
          .filter(_.name.isValidContainerName)
      }

      val pipeline: HttpRequest => Future[Seq[DockerContainer]] =
        sendAndReceive ~>[HttpResponse,Future[JsValue],Future[JsValue]] reqToJson ~> parseJsonResponse

      pipeline {
        Get(baseUrl + "containers/json?all=1")
      }

    }

  }

  private def reqToJson(res: HttpResponse): Future[JsValue] =
    sprayJsValueUnmarshaller(mat)(res.entity)

  implicit class ContainerNameTester(str: String) {
    // Same as domain name, but use of capitals is prohibited because container
    // names are case-sensitive while host names should be case-insensitive.
    def isValidContainerName = {
      !str.isEmpty &&
      str.length <= 63 &&
      !str.startsWith("-") &&
      !str.endsWith("-") &&
      str.matches("[a-z0-9\\-]+")
    }
  }

}
