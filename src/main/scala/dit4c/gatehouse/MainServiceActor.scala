package dit4c.gatehouse

import akka.actor.Actor
import dit4c.gatehouse.docker.DockerClient
import dit4c.gatehouse.docker.DockerIndexActor
import spray.http.Uri
import akka.actor.Props
import dit4c.gatehouse.auth.AuthActor

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class MainServiceActor(config: Config) extends Actor with MiscService {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  val dockerClient = new DockerClient(Uri("http://127.0.0.1:4243/"))
  val dockerIndex = actorRefFactory.actorOf(
      Props(classOf[DockerIndexActor], dockerClient))
  val auth = actorRefFactory.actorOf(
      Props(classOf[AuthActor], config.keyFile))

  // this actor only runs our route, but you could add
  // other things here, like request stream processing
  // or timeout handling
  def receive = runRoute(AuthService(context, dockerIndex, auth).route ~ miscRoute)
}