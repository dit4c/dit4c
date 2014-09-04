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
import scala.concurrent.Future

@RunWith(classOf[JUnitRunner])
class HipacheClientSpec extends RedisStandaloneServer {

  import ByteStringDeserializer.String

  // Tests are separated by prefix
  def config(prefix: String): Hipache.ServerConfig =
    Hipache.ServerConfig(
      RedisServer("127.0.0.1", port, None, None),
      prefix
    )

  def await[A](f: Future[A]) = Await.result(f, timeOut)
  def idxKey(prefix: String) = s"${prefix}frontends"

  def withClient[A](c: Hipache.ServerConfig)(f: HipacheClient => A) = {
    val client = new HipacheClient(c)
    val retval = f(client)
    client.disconnect
    retval
  }


  import Hipache._

  "HipacheClient" >> {

    "adds mappings" >> {
      val c = config("testadd:")
      val client = new HipacheClient(c)

      val frontend = Frontend("test1", "test1.example.test")
      val backend = Backend("example.test")

      await(client.put(frontend, backend))
      client.disconnect

      val key = s"${c.prefix}frontend:${frontend.domain}"
      await(redis.sismember[String](idxKey(c.prefix), key)) must beTrue
      await(redis.lrange[String](key, 0, -1)) match {
        case Seq(name, backend) =>
          name must_== frontend.name
          backend must_== backend.toString
      }
    }

    "removes mappings" >> {
      val c = config("testremove:")
      val frontend = Frontend("test1", "test1.example.test")
      val backend = Backend("example.test")

      val key = s"${c.prefix}frontend:${frontend.domain}"

      val setup = for {
        _ <- redis.sadd(idxKey(c.prefix), key)
        _ <- redis.rpush(key, frontend.name)
        _ <- redis.rpush(key, backend.toString)
      } yield "done"
      await(setup)

      await(redis.exists(key)) must beTrue

      await(redis.lrange[String](key, 0, -1)) match {
        case Seq(name, backend) =>
          name must_== frontend.name
          backend must_== backend.toString
      }


      withClient(c) { client =>
        await(client.delete(frontend))
      }

      await(redis.sismember[String](idxKey(c.prefix), key)) must beFalse
      await(redis.exists(key)) must beFalse
    }

    "replaces mappings" >> {
      val c = config("testreplace:")

      val frontend = Frontend("test", "test.example.test")
      val key = s"${c.prefix}frontend:${frontend.domain}"
      val requests =
        Seq(
          (frontend, Backend("example1.test")),
          (frontend, Backend("example2.test")))

      await(redis.exists(key)) must beFalse

      withClient(c) { client =>
        requests.foreach { case (frontend, backend) =>
          await(client.put(frontend, backend))

          await(redis.sismember[String](idxKey(c.prefix), key)) must beTrue
          await(redis.exists(key)) must beTrue

          await(redis.lrange[String](key, 0, -1)) match {
            case Seq(name, backend) =>
              name must_== frontend.name
              backend must_== backend.toString
          }
        }
      }
      done
    }

    "retrieves mappings" >> {
      val c = config("testretrieve:")

      val frontend = Frontend("test", "test.example.test")
      val backend = Backend("example.test")
      val key = s"${c.prefix}frontend:${frontend.domain}"

      val setup = for {
        _ <- redis.sadd(idxKey(c.prefix), key)
        _ <- redis.rpush(key, frontend.name)
        _ <- redis.rpush(key, backend.toString)
      } yield "done"
      await(setup)

      withClient(c) { client =>
        await(client.get(frontend)).get must_== backend

        val map = await(client.all)
        map.size must_== 1
        map must haveKey(frontend)
        map(frontend) must_== backend
      }
    }

  }


}