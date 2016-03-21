package dit4c.gatehouse.docker

import scala.concurrent.Future
import scala.concurrent.Promise
import org.specs2.mutable.Specification
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.github.dockerjava.core.DockerClientConfig
import org.specs2.concurrent.ExecutionEnv
import org.specs2.specification.BeforeAfterAll
import scala.concurrent.ExecutionContext
import java.io.Closeable

class DockerIndexActorSpec extends Specification with BeforeAfterAll {
  import scala.concurrent.duration._

  val system = ActorSystem("testSystem")
  implicit val timeout = Timeout(100.millis)

  def client = new DockerClient(null /* Not a real client, so OK */) {
    import DockerClient.{ContainerEvent,ContainerPortMapping}
    override lazy val dockerClient = ???
    override def events(callback: (ContainerEvent) => Unit): (Future[Closeable], Future[Unit]) = {
      // TODO: Mimic event feed. For now, do nothing
      val p = Promise[Unit]()
      val closer = new Closeable() { override def close = p.success(()) }
      (Future.successful(closer), p.future)
    }
    override def containerPort(
        containerId: String)(implicit ec: ExecutionContext): Future[Option[ContainerPortMapping]] =
          Future.successful {
            containerId match {
              case s if s == "0001" =>
                Some(ContainerPortMapping(s, "foo", "1.2.3.4:8080"))
              case s if s == "0002" =>
                Some(ContainerPortMapping(s, "bar", "2.3.4.5:8888"))
              case _ => None
            }
          }
    override def containerIds(implicit ec: ExecutionContext): Future[Set[String]] =
      Future.successful(Set("0001","0002"))
  }

  override def beforeAll = {}

  override def afterAll = {
    system.shutdown()
  }

  "DockerIndexActor" should {

    "respond to queries" in { implicit ee: ExecutionEnv =>
      import DockerIndexActor._
      val index: ActorRef = system.actorOf(Props(classOf[DockerIndexActor], client))

      (index ask PortQuery("foo"))
        .mapTo[PortReply] must equalTo(PortReply(Some("1.2.3.4:8080")))
        .await(retries = 2, timeout = 200.millis)

      (index ask PortQuery("bar"))
        .mapTo[PortReply] must equalTo(PortReply(Some("2.3.4.5:8888")))
        .await(retries = 2, timeout = 200.millis)

      (index ask PortQuery("doesnotexist"))
        .mapTo[PortReply] must equalTo(PortReply(None))
        .await(retries = 2, timeout = 200.millis)

      done
    }
  }
}
