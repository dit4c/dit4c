package providers.hipache

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import scala.concurrent.Await
import scala.util.Random
import akka.actor.Props
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.agent.Agent
import scala.concurrent.Future
import net.nikore.etcd._
import play.api.libs.json.Json

@RunWith(classOf[JUnitRunner])
class HipacheClientSpec extends Specification {
  import scala.concurrent.duration._
  
  implicit val system = ActorSystem("hipacheClientTests")

  class MockEtcdClient extends EtcdClient("") {
    
    import scala.concurrent.ExecutionContext.Implicits.global
    import akka.agent.Agent
    import EtcdJsonProtocol._
    
    val counter: Agent[Int] = Agent(0)
    val m: Agent[Map[String, NodeResponse]] = Agent(Map.empty)
        
    override def getKey(key: String): Future[EtcdResponse] = 
      for {
        count <- counter alter (_ + 1)
        lookup <- m.future
        nr = lookup get key
      } yield nr match {
        case Some(nr) => EtcdResponse("get", nr, None)
        case None =>
          throw EtcdExceptions.KeyNotFoundException(
              "Key not found", "not found", count)
      }
  
    override def getKeyAndWait(key: String, wait: Boolean = true): Future[EtcdResponse] = ???
  
    override def setKey(key: String, value: String): Future[EtcdResponse] =
      (counter alter (_ + 1)) flatMap { count =>
        getKey(key)
          .map(_.node)
          .map(nr => (nr.copy(value=Some(value), modifiedIndex=count), Some(nr)))
          .recover {
            case _: EtcdExceptions.KeyNotFoundException =>
              (NodeResponse(key, Some(value), count, count), None)
          }
          .map { case (nr, pnr) =>
            m alter {_ + (key -> nr)}
            EtcdResponse("set", nr, pnr)
          }
      }
  
    override def deleteKey(key: String): Future[EtcdResponse] =
      (counter alter (_ + 1)) flatMap { count =>
        getKey(key)
          .map(_.node)
          .map { nr =>
            m alter {_ - key}
            val dnr = nr.copy(value=None, modifiedIndex=count)
            EtcdResponse("delete", dnr, Some(nr))
          }
      }
  
    override def createDir(dir: String): Future[EtcdResponse] = ???
  
    // Dodgy implementation
    override def listDir(dir: String, recursive: Boolean = false) =
      if (recursive) ???
      else
        for {
          count <- counter alter (_ + 1)
          lookup <- m.future
        } yield {
          val matching = lookup.filterKeys(_.startsWith(dir))
          if (!matching.isEmpty) {
            EtcdListResponse(
                "get",
                NodeListElement(
                    dir,
                    Some(true),
                    matching.get(dir).flatMap(_.value),
                    Some((matching - dir).values.map { nr =>
                      NodeListElement(nr.key, None, nr.value, None)
                    }.toList)))
          } else {
            throw EtcdExceptions.KeyNotFoundException(
              "Key not found", "not found", count)
          }
        }
  
    override def deleteDir(dir: String, recursive: Boolean = false) = ???
      
  }
  
  
  // Tests are separated by prefix
  def config(prefix: String): Hipache.ServerConfig =
    Hipache.ServerConfig(
      new MockEtcdClient,
      prefix
    )

  def await[A](f: Future[A]) = Await.result(f, Duration(5, "seconds"))

  import Hipache._
  import EtcdJsonProtocol._

  "HipacheClient" >> {

    "adds mappings" >> {
      val c = config("testadd")
      val client = new HipacheClient(c)

      val frontend = Frontend("test1", "test1.example.test")
      val backend = Backend("example.test")

      await(client.put(frontend, backend))

      val key = s"${c.prefix}/frontend:${frontend.domain}"
      await(c.client.getKey(key)) match {
        case EtcdResponse("get", NodeResponse(_, Some(value), _, _), None) =>
          Json.parse(value) must_== Json.arr(frontend.name, backend.toString)
      }
    }

    "removes mappings" >> {
      val c = config("testremove")
      val frontend = Frontend("test1", "test1.example.test")
      val backend = Backend("example.test")
      val client = new HipacheClient(c)

      val key = s"${c.prefix}/frontend:${frontend.domain}"

      // Create key
      await(c.client.setKey(
            key,
            Json.stringify(Json.arr(frontend.name, backend.toString))))

      await(c.client.getKey(key)).node.value must_==
        Some(Json.stringify(Json.arr(frontend.name, backend.toString)))

      await(client.delete(frontend))

      await(c.client.getKey(key)) must
        throwA[EtcdExceptions.KeyNotFoundException]
    }

    "replaces mappings" >> {
      val c = config("testreplace")
      val client = new HipacheClient(c)
      val frontend = Frontend("test", "test.example.test")
      val key = s"${c.prefix}/frontend:${frontend.domain}"
      val requests =
        Seq(
          (frontend, Backend("example1.test")),
          (frontend, Backend("example2.test")))

      requests.foreach { case (frontend, backend) =>
        await(client.put(frontend, backend))

        await(c.client.getKey(key)).node.value must_==
          Some(Json.stringify(Json.arr(frontend.name, backend.toString)))
      }
      done
    }

    "retrieves mappings" >> {
      val c = config("testretrieve")
      val client = new HipacheClient(c)
      val frontend = Frontend("test", "test.example.test")
      val backend = Backend("example.test")
      val key = s"${c.prefix}/frontend:${frontend.domain}"

      await(client.all).size must_== 0

      await(c.client.setKey(
          key,
          Json.stringify(Json.arr(frontend.name, backend.toString))))

      await(client.get(frontend)).get must_== backend

      val map = await(client.all)
      map.size must_== 1
      map must haveKey(frontend)
      map(frontend) must_== backend
    }
  }


}