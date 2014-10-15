package dit4c.machineshop

import akka.util.Timeout
import java.util.concurrent.TimeUnit
import org.specs2.mutable.Specification
import spray.testkit.Specs2RouteTest
import spray.http._
import StatusCodes._
import spray.routing.HttpService
import dit4c.machineshop.docker.DockerClient
import dit4c.machineshop.docker.models._
import dit4c.machineshop.images._
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import akka.actor.{ActorRef, Props}
import dit4c.machineshop.auth.SignatureActor
import akka.testkit.TestProbe
import akka.testkit.TestActor
import scala.util.Random
import scalax.file.ramfs.RamFileSystem
import java.util.Calendar

class ApiServiceSpec extends Specification with Specs2RouteTest with HttpService {
  implicit val actorRefFactory = system

  import spray.util.pimpFuture
  implicit val timeout = new Timeout(5, TimeUnit.SECONDS)

  def ephemeralKnownImages = new KnownImages(
    RamFileSystem().fromString("/known_images.json"))
  
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
            Random.nextString(20),
            Set(s"$imageName:$tagName"),
            Calendar.getInstance()))
        })
    }

    override val containers = new DockerContainers {
      override def create(name: String, image: DockerImage) = Future.successful({
        val newContainer =
          new MockDockerContainer(UUID.randomUUID.toString, name, image)
        containerList = containerList ++ Seq(newContainer)
        newContainer
      })
      override def list = Future.successful(containerList)
    }
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
        Props(classOf[ImageMonitoringActor], knownImages, client, None))
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
      import spray.httpx.SprayJsonSupport._
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
      import spray.httpx.SprayJsonSupport._
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
      val authHeader = HttpHeaders.Authorization(
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
      import spray.httpx.SprayJsonSupport._
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
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar", image).await
      dc.isRunning must beFalse
      Post("/containers/foobar/start") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        responseAs[JsObject].fields("active") must_== JsBoolean(true)
      }
      dc.refresh.await.isRunning must_== true
    }

    "stop containers with POST requests to /containers/:name/stop" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar", image).flatMap(_.start).await
      dc.isRunning must beTrue;
      Post("/containers/foobar/stop") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        responseAs[JsObject].fields("active") must_== JsBoolean(false)
      }
      dc.refresh.await.isRunning must_== false
    }

    "return a MethodNotAllowed error for DELETE requests to /containers" in {
      implicit val client = mockDockerClient
      Put("/containers") ~> sealRoute(route) ~> check {
        status === MethodNotAllowed
        responseAs[String] ===
          "HTTP method not allowed, supported methods: GET, POST"
      }
    }
    
    "create new known image with POST requests to /images" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
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
      import spray.httpx.SprayJsonSupport._
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
      Get("/images") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String])
        json must beAnInstanceOf[JsArray]
        json.asInstanceOf[JsArray].elements must haveSize(4)
      }
    }
    
    "pull images with POST request to /images/:id/pull" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
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
