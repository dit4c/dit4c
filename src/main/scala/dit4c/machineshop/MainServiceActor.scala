package dit4c.machineshop

import akka.actor.{Actor, Props}
import dit4c.machineshop.docker.DockerClient
import spray.http.Uri
import spray.routing.HttpService
import spray.routing.RequestContext

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class MainServiceActor(config: Config) extends Actor with HttpService {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  val dockerClient = new DockerClient(Uri("http://127.0.0.1:4243/"))


  implicit def rp2route(rp: RouteProvider): RequestContext => Unit = rp.route

  // this actor only runs our route, but you could add
  // other things here, like request stream processing
  // or timeout handling
  def receive = runRoute(MiscService(actorRefFactory))
}