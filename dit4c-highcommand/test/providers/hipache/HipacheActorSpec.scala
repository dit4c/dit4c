package providers.hipache

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import scala.concurrent.Await
import redis._
import scala.util.Random
import akka.actor.Props
import akka.pattern.ask

@RunWith(classOf[JUnitRunner])
class HipacheActorSpec extends RedisStandaloneServer {

  import ByteStringDeserializer.String

  // Tests are separated by prefix
  def config(prefix: String): Hipache.ServerConfig =
    Hipache.ServerConfig(
      RedisServer("127.0.0.1", port, None, None),
      prefix
    )

  import Hipache._
  import HipacheActor._

  "HipacheActor" >> {

    "adds mappings" >> {
      val c = config("testadd:")
      val actor = system.actorOf(Props(classOf[HipacheActor], c))

      val request = Put(
        Frontend("test1", "test1.example.test"),
        Backend("example.test")
      )

      val result = Await.result(actor ask request, timeOut)
      result must_== HipacheActor.OK

      val key = s"${c.prefix}frontend:${request.frontend.domain}"
      Await.result(redis.lrange[String](key, 0, -1), timeOut) match {
        case Seq(name, backend) =>
          name must_== request.frontend.name
          backend must_== request.backend.toString
      }
    }

    "removes mappings" >> {
      val c = config("testremove:")
      val frontend = Frontend("test1", "test1.example.test")
      val backend = Backend("example.test")

      val key = s"${c.prefix}frontend:${frontend.domain}"

      val setup = for {
        _ <- redis.rpush(key, frontend.name)
        _ <- redis.rpush(key, backend.toString)
      } yield "done"

      Await.result(redis.exists(key), timeOut) must beTrue

      Await.result(redis.lrange[String](key, 0, -1), timeOut) match {
        case Seq(name, backend) =>
          name must_== frontend.name
          backend must_== backend.toString
      }

      val actor = system.actorOf(Props(classOf[HipacheActor], c))

      val result = Await.result(actor ask Delete(frontend), timeOut)
      result must_== HipacheActor.OK

      Await.result(redis.exists(key), timeOut) must beFalse
    }

    "replaces mappings" >> {
      val c = config("testreplace:")
      val actor = system.actorOf(Props(classOf[HipacheActor], c))

      val frontend = Frontend("test", "test.example.test")
      val key = s"${c.prefix}frontend:${frontend.domain}"
      val requests =
        Seq(
          Put(frontend, Backend("example1.test")),
          Put(frontend, Backend("example2.test")))

      Await.result(redis.exists(key), timeOut) must beFalse

      requests.foreach { request =>
        val result = Await.result(actor ask request, timeOut)
        result must_== HipacheActor.OK

        Await.result(redis.exists(key), timeOut) must beTrue

        Await.result(redis.lrange[String](key, 0, -1), timeOut) match {
          case Seq(name, backend) =>
            name must_== request.frontend.name
            backend must_== request.backend.toString
        }
      }
      done
    }

  }


}