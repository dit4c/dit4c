package dit4c.machineshop

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

  def mockDockerClient = new DockerClient {
    var containerList: Seq[DockerContainer] = Nil

    case class MockDockerContainer(
        val id: String,
        val name: String,
        val status: ContainerStatus = ContainerStatus.Stopped) extends DockerContainer {
      override def refresh = Future.successful(this)
      override def start = Future.successful({
        val obj = new MockDockerContainer(id, name, ContainerStatus.Running)
        containerList = containerList.filterNot(_ == this) ++ Seq(obj)
        obj
      })
      override def stop(timeout: Duration) = Future.successful({
        val obj = new MockDockerContainer(id, name, ContainerStatus.Stopped)
        containerList = containerList.filterNot(_ == this) ++ Seq(obj)
        obj
      })
      override def delete = ???
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

  val route = ApiService(mockDockerClient).route

  "ApiService" should {

    "return all containers for GET requests to /projects" in {
      Get("/projects") ~> route ~> check {
        import spray.json._
        import DefaultJsonProtocol._
        val json = JsonParser(responseAs[String])
        json must beAnInstanceOf[JsArray]
        json.asInstanceOf[JsArray].elements must beEmpty
        done
      }
    }

    "return a MethodNotAllowed error for DELETE requests to /projects" in {
      Put("/projects") ~> sealRoute(route) ~> check {
        status === MethodNotAllowed
        responseAs[String] === "HTTP method not allowed, supported methods: GET"
      }
    }
  }
}
