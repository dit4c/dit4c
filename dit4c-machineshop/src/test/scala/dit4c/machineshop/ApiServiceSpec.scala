package dit4c.machineshop

import akka.util.Timeout
import java.util.concurrent.TimeUnit
import org.specs2.mutable.Specification
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.client.RequestBuilding._
import StatusCodes._
import dit4c.machineshop.docker.DockerClient
import dit4c.machineshop.docker.models._
import dit4c.machineshop.images._
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import akka.actor.{ActorRef, Props}
import dit4c.machineshop.auth.SignatureActor
import akka.testkit.TestProbe
import akka.testkit.TestActor
import scala.util.Random
import scalax.file.ramfs.RamFileSystem
import java.util.Calendar
import java.security.MessageDigest
import akka.http.scaladsl.testkit.RouteTest
import dit4c.Specs2TestInterface
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.headers.EntityTagRange
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import scala.concurrent.Await

class ApiServiceSpec extends Specification with RouteTest with Specs2TestInterface {
  implicit val actorRefFactory = system
  implicit val timeout = new Timeout(5, TimeUnit.SECONDS)

  def ephemeralKnownImages = new KnownImages(
    RamFileSystem().fromString("/known_images.json"))

  implicit class FutureAwaitable[T](f: Future[T]) {
    def await(implicit timeout: Timeout) = Await.result(f, timeout.duration)
  }

  def mockDockerClient = new DockerClient {
    var containerList: Seq[DockerContainer] = Nil
    var imageList: Seq[DockerImage] = Nil

    case class MockDockerContainer(
        val id: String,
        val name: String,
        val image: String,
        val status: ContainerStatus = ContainerStatus.Stopped) extends DockerContainer {
      override def refresh = Future.successful({
        containerList.find(_.name == name).get
      })
      override def start = Future.successful({
        updateList(
            new MockDockerContainer(id, name, image, ContainerStatus.Running))
      })
      override def stop(timeout: Duration) = Future.successful({
        updateList(
            new MockDockerContainer(id, name, image, ContainerStatus.Stopped))
      })
      /*
      override def export(onChunk: HttpEntity.ChunkStreamPart => Future[Unit]) = {
        val data: Array[Byte] = Array.fill(16)(0) // Empty tar
        val entity = HttpEntity(
            ContentType(MediaTypes.`application/x-tar`, None), data)
        HttpResponse(entity = entity).asPartStream(8).foreach(onChunk)
      }*/
      override def delete = Future.successful({
        containerList = containerList.filterNot(_ == this)
      })
      private def updateList(changed: DockerContainer): DockerContainer = {
        containerList = containerList.filterNot(_ == this) ++ Seq(changed)
        changed
      }
    }

    case class MockDockerImage(
        val id: String,
        val names: Set[String],
        val created: Calendar) extends DockerImage

    override val images = new DockerImages {
      override def list = Future.successful(imageList)
      override def pull(imageName: String, tagName: String) =
        Future.successful({
          imageList = imageList ++ Seq(MockDockerImage(
            sha1(s"$imageName:$tagName"),
            Set(s"$imageName:$tagName"),
            dummyCreationDate))
        })
      protected val dummyCreationDate = Calendar.getInstance()
    }

    override val containers = new DockerContainers {
      override def create(name: String, image: DockerImage) = Future.successful({
        val newContainer =
          new MockDockerContainer(sha1(s"$name:$image"), name, image)
        containerList = containerList ++ Seq(newContainer)
        newContainer
      })
      override def list = Future.successful(containerList)
    }

    protected def sha1(text: String) =
      MessageDigest.getInstance("SHA-1")
        .digest(text.getBytes("UTF-8"))
        .map("%02x".format(_)).mkString
  }

  def mockSignatureActor(response: SignatureActor.AuthResponse): ActorRef = {
    val probe = TestProbe()
    probe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case SignatureActor.AuthCheck(_) =>
            sender ! response
            TestActor.KeepRunning
        }
    })
    probe.ref
  }

  val image = "dit4c/dit4c-container-ipython"

  def route(implicit
      client: DockerClient,
      knownImages: KnownImages = ephemeralKnownImages,
      signatureActor: Option[ActorRef] = None) = {
    val imageMonitor = actorRefFactory.actorOf(
        Props(classOf[ImageManagementActor], knownImages, client, None))
    ApiService(client, imageMonitor, signatureActor).route
  }

  "ApiService" should {

    "return all containers for GET requests to /containers" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      Get("/containers") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String])
        json must beAnInstanceOf[JsArray]
        json.asInstanceOf[JsArray].elements must beEmpty
      }
      client.containers.create("foobar", image).await
      Get("/containers") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String])
        json must beAnInstanceOf[JsArray]
        json.asInstanceOf[JsArray].elements must haveSize(1)
        val container = json.asInstanceOf[JsArray].elements.head.asJsObject
        container.fields("name").convertTo[String]  must_== "foobar"
      }
    }

    "return containers for GET requests to /containers/:name" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      Get("/containers/foobar") ~> route ~> check {
        status must_== StatusCodes.NotFound
      }
      client.containers.create("foobar", image).await
      Get("/containers/foobar") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String]).convertTo[JsObject]
        json.fields("name").convertTo[String] must_== "foobar"
      }
    }

    "create containers with POST requests to /containers" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      client.containers.list.await must beEmpty
      val requestJson = JsObject(
          "name" -> JsString("foobar"),
          "image" -> JsString(image))
      Post("/containers", requestJson) ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String]).convertTo[JsObject]
        json.fields("name").convertTo[String] must_== "foobar"
      }
      client.containers.list.await must haveSize(1)
    }

    "require HTTP Signatures for POST requests to /containers" in {
      import spray.json._
      import DefaultJsonProtocol._
      val client = mockDockerClient
      val knownImages = ephemeralKnownImages
      client.containers.list.await must beEmpty
      val requestJson = JsObject(
          "name" -> JsString("foobar"),
          "image" -> JsString(image))
      // Check a challenge is issued
      Post("/containers", requestJson) ~>
          route(client, ephemeralKnownImages, Some(mockSignatureActor(
              SignatureActor.AccessDenied("Just 'cause")))) ~>
          check {
        status must_== StatusCodes.Unauthorized
        header("WWW-Authenticate") must beSome
      }
      client.containers.list.await must beEmpty
      // Create a Authorization header
      val authHeader = Authorization(
              GenericHttpCredentials("Signature", "",
                  Map(/* Parameters don't matter - using mock */ )))
      // Check that signature failure works
      Post("/containers", requestJson) ~>
          addHeader(authHeader) ~>
          route(client, ephemeralKnownImages, Some(mockSignatureActor(
              SignatureActor.AccessDenied("Just 'cause")))) ~>
          check {
        status must_== StatusCodes.Forbidden
      }
      client.containers.list.await must beEmpty
      Post("/containers", requestJson) ~>
          addHeader(authHeader) ~>
          route(client, ephemeralKnownImages, Some(mockSignatureActor(
              SignatureActor.AccessGranted))) ~>
          check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String]).convertTo[JsObject]
        json.fields("name").convertTo[String] must_== "foobar"
      }
      client.containers.list.await must haveSize(1)
    }

    "delete containers with DELETE requests to /containers/:name" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar", image).await
      client.containers.list.await must haveSize(1)
      Delete("/containers/foobar") ~> route ~> check {
        status must_== StatusCodes.NoContent
      }
    }

    "start containers with POST requests to /containers/:name/start" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar", image).await
      dc.isRunning must beFalse
      Post("/containers/foobar/start") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        responseAs[JsValue].asJsObject.fields("active") must_== JsBoolean(true)
      }
      dc.refresh.await.isRunning must_== true
    }

    "stop containers with POST requests to /containers/:name/stop" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar", image).flatMap(_.start).await
      dc.isRunning must beTrue;
      Post("/containers/foobar/stop") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        responseAs[JsValue].asJsObject.fields("active") must_== JsBoolean(false)
      }
      dc.refresh.await.isRunning must_== false
    }

    "export containers with POST requests to /containers/:name/export" in {
      pending
      /*
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar", image).await
      Get("/containers/foobar/export") ~> route ~> check {
        status must_== StatusCodes.OK
        header("Content-Type").get.value must_== "application/x-tar"
        forall(chunks) { chunk =>
          val bytes = chunk.data.toArray
          bytes must haveSize(8)
          forall(bytes)((b) => b must_== 0)
        }
      }
      */
    }

    "return a MethodNotAllowed error for DELETE requests to /containers" in {
      implicit val client = mockDockerClient
      Put("/containers") ~> Route.seal(route) ~> check {
        status === MethodNotAllowed
        responseAs[String] ===
          "HTTP method not allowed, supported methods: GET, POST"
      }
    }

    "create new known image with POST requests to /images" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      client.images.list.await must beEmpty
      val requestJson = JsObject(
          "displayName" -> JsString("BusyBox"),
          "repository" -> JsString("busybox"),
          "tag" -> JsString("latest"))
      Post("/images", requestJson) ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String]).convertTo[JsObject]
        json.fields("id").convertTo[String] must beMatching("[a-z0-9]+")
        json.fields("displayName").convertTo[String] must_== "BusyBox"
        json.fields("repository").convertTo[String] must_== "busybox"
        json.fields("tag").convertTo[String] must_== "latest"
      }
      client.images.list.await must haveSize(1)
    }

    "list images with GET request to /images" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val knownImages = ephemeralKnownImages
      implicit val client = mockDockerClient
      Get("/images") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String])
        json must beAnInstanceOf[JsArray]
        json.asInstanceOf[JsArray].elements must beEmpty
      }
      17.to(20).foreach { i =>
        knownImages += KnownImage(s"Fedora $i", "fedora", i.toString)
        client.images.pull("fedora", i.toString).await
      }
      var etag: EntityTag = null
      val sameRoute = route(client, knownImages)
      Get("/images") ~> sameRoute ~> check {
        contentType must_== ContentTypes.`application/json`
        header[ETag] must beSome[ETag]
        etag = header[ETag].get.etag
        val json = JsonParser(responseAs[String])
        json must beAnInstanceOf[JsArray]
        json.asInstanceOf[JsArray].elements must haveSize(4)
      }
      val ifNoneMatch = `If-None-Match`(EntityTagRange(etag))
      Get("/images") ~> addHeaders(ifNoneMatch) ~> sameRoute ~> check {
        println(responseAs[String])
        status must_== StatusCodes.NotModified
      }
    }

    "pull images with POST request to /images/:id/pull" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val knownImages = ephemeralKnownImages
      implicit val client = mockDockerClient
      val knownImage = KnownImage("Fedora 20", "fedora", "20")
      knownImages += knownImage
      Post(s"/images/${knownImage.id}/pull") ~> route ~> check {
        status must_== StatusCodes.Accepted
      }
    }

  }
}
