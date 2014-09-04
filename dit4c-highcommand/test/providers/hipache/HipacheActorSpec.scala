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
class HipacheActorSpec extends RedisStandaloneServer {

  import ByteStringDeserializer.String

  // Tests are separated by prefix
  def config(prefix: String): Hipache.ServerConfig =
    Hipache.ServerConfig(
      RedisServer("127.0.0.1", port, None, None),
      prefix
    )

  def await[A](f: Future[A]) = Await.result(f, timeOut)
  def idxKey(prefix: String) = s"${prefix}frontends"

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

      val result = await(actor ask request)
      result must_== HipacheActor.OK(())

      val key = s"${c.prefix}frontend:${request.frontend.domain}"
      await(redis.sismember[String](idxKey(c.prefix), key)) must beTrue
      await(redis.lrange[String](key, 0, -1)) match {
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

      val actor = system.actorOf(Props(classOf[HipacheActor], c))

      val result = await(actor ask Delete(frontend))
      result must_== HipacheActor.OK(())

      await(redis.sismember[String](idxKey(c.prefix), key)) must beFalse
      await(redis.exists(key)) must beFalse
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

      await(redis.exists(key)) must beFalse

      requests.foreach { request =>
        val result = await(actor ask request)
        result must_== HipacheActor.OK(())

        await(redis.sismember[String](idxKey(c.prefix), key)) must beTrue
        await(redis.exists(key)) must beTrue

        await(redis.lrange[String](key, 0, -1)) match {
          case Seq(name, backend) =>
            name must_== request.frontend.name
            backend must_== request.backend.toString
        }
      }
      done
    }

    "retrieves mappings" >> {
      val c = config("testretrieve:")
      val actor = system.actorOf(Props(classOf[HipacheActor], c))

      val frontend = Frontend("test", "test.example.test")
      val backend = Backend("example.test")
      val key = s"${c.prefix}frontend:${frontend.domain}"

      val setup = for {
        _ <- redis.sadd(idxKey(c.prefix), key)
        _ <- redis.rpush(key, frontend.name)
        _ <- redis.rpush(key, backend.toString)
      } yield "done"
      await(setup)

      await(actor ask Get(frontend)).asInstanceOf[OK[Option[Backend]]]
        .value.get must_== backend

      val map = await(actor ? All).asInstanceOf[OK[Map[Frontend,Backend]]].value
      map.size must_== 1
      map must haveKey(frontend)
      map(frontend) must_== backend
    }

  }


}