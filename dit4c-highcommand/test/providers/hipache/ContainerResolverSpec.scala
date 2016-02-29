package providers.hipache

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import models.Container
import utils.SpecUtils
import play.api.test.WithApplication
import providers.RoutingMapEmitter
import providers.ContainerResolver

@RunWith(classOf[JUnitRunner])
class ContainerResolverSpec extends Specification with SpecUtils {
  
  def container = new Container {
    // Members declared in models.BaseModel
    def _rev: Option[String] = ???
    def id: String = "deadbeefdeadbeefdeadbeef"

    // Members declared in models.Container
    def computeNodeId: String = ???
    def delete: scala.concurrent.Future[Unit] = ???
    def image: String = ???
    def name: String = ???
    def update: models.Container.UpdateOp = ???

    // Members declared in models.OwnableModel
    def ownerID: String = ???
  }

  "ContainerResolver" >> {

    "resolves Container to a machineshop container name" >> {
      val resolver = new ContainerResolver(fakeApp)
      val name = resolver.asName(container)
      name must startWith("c-")
      name must endWith(container.id)
    }

    "resolves Container to URL for redirects" >> {
      val resolver = new ContainerResolver(fakeApp)
      val url = resolver.asUrl(container)
      url.getProtocol must_== "http"
      url.getHost must_== s"c-${container.id}.localhost.localdomain"
      url.getPath must_== "/"
    }

    "resolves Container to Hipache.Frontend" >> {
      val resolver = new ContainerResolver(fakeApp)
      val frontend = resolver.asFrontend(container)
      frontend.name must contain(container.id)
      frontend.domain must endWith(".localhost.localdomain")
      frontend.domain must startWith("c-")
      frontend.domain must contain(container.id)
    }

    "can determine if Hipache.Frontend is a Container" >> {
      val resolver = new ContainerResolver(fakeApp)
      resolver.isContainerFrontend(
          resolver.asFrontend(container)) must beTrue
      resolver.isContainerFrontend(
          RoutingMapEmitter.Frontend("portal", "localhost.localdomain")) must beFalse
      resolver.isContainerFrontend(
          RoutingMapEmitter.Frontend("other", "c-foo.example.test")) must beFalse
    }
  }

}