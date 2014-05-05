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
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.Duration

class ApiServiceSpec extends Specification with Specs2RouteTest with HttpService {
  implicit val actorRefFactory = system

  import spray.util.pimpFuture
  implicit val timeout = new Timeout(5, TimeUnit.SECONDS)


  def mockDockerClient = new DockerClient {
    var containerList: Seq[DockerContainer] = Nil

    case class MockDockerContainer(
        val id: String,
        val name: String,
        val status: ContainerStatus = ContainerStatus.Stopped) extends DockerContainer {
      override def refresh = Future.successful({
        containerList.find(_.name == name).get
      })
      override def start = Future.successful({
        updateList(new MockDockerContainer(id, name, ContainerStatus.Running))
      })
      override def stop(timeout: Duration) = Future.successful({
        updateList(new MockDockerContainer(id, name, ContainerStatus.Stopped))
      })
      override def delete = Future.successful({
        containerList = containerList.filterNot(_ == this)
      })
      private def updateList(changed: DockerContainer): DockerContainer = {
        containerList = containerList.filterNot(_ == this) ++ Seq(changed)
        changed
      }
    }

    override val containers = new DockerContainers {
      override def create(name: String) = Future.successful({
        val newContainer =
          new MockDockerContainer(UUID.randomUUID.toString, name)
        containerList = containerList ++ Seq(newContainer)
        newContainer
      })

      override def list = Future.successful(containerList)
    }
  }

  def route(implicit client: DockerClient) = ApiService(client).route

  "ApiService" should {

    "return all containers for GET requests to /projects" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      Get("/projects") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String])
        json must beAnInstanceOf[JsArray]
        json.asInstanceOf[JsArray].elements must beEmpty
      }
      client.containers.create("foobar").await
      Get("/projects") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String])
        json must beAnInstanceOf[JsArray]
        json.asInstanceOf[JsArray].elements must haveSize(1)
        val container = json.asInstanceOf[JsArray].elements.head.asJsObject
        container.fields("name").convertTo[String]  must_== "foobar"
      }
    }

    "return containers for GET requests to /projects/:name" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      Get("/projects/foobar") ~> route ~> check {
        status must_== StatusCodes.NotFound
      }
      client.containers.create("foobar").await
      Get("/projects/foobar") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String]).convertTo[JsObject]
        json.fields("name").convertTo[String] must_== "foobar"
      }
    }

    "create containers with POST requests to /projects/new" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      client.containers.list.await must beEmpty
      val requestJson = JsObject("name" -> JsString("foobar"))
      Post("/projects/new", requestJson) ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String]).convertTo[JsObject]
        json.fields("name").convertTo[String] must_== "foobar"
      }
      client.containers.list.await must haveSize(1)
    }

    "delete containers with DELETE requests to /projects/:name" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar").await
      client.containers.list.await must haveSize(1)
      Delete("/projects/foobar") ~> route ~> check {
        status must_== StatusCodes.NoContent
      }
      client.containers.list.await must beEmpty
    }

    "start containers with POST requests to /projects/:name/start" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar").await
      dc.isRunning must beFalse
      Post("/projects/foobar/start") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        responseAs[JsObject].fields("active") must_== JsBoolean(true)
      }
      dc.refresh.await.isRunning must_== true
    }

    "stop containers with POST requests to /projects/:name/stop" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar").flatMap(_.start).await
      dc.isRunning must beTrue;
      Post("/projects/foobar/stop") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        responseAs[JsObject].fields("active") must_== JsBoolean(false)
      }
      dc.refresh.await.isRunning must_== false
    }

    "return a MethodNotAllowed error for DELETE requests to /projects" in {
      implicit val client = mockDockerClient
      Put("/projects") ~> sealRoute(route) ~> check {
        status === MethodNotAllowed
        responseAs[String] === "HTTP method not allowed, supported methods: GET"
      }
    }
  }
}
