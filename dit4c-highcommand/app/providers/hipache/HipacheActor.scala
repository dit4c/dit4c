package providers.hipache

import akka.actor.Actor
import redis.RedisServer
import redis.RedisClient
import scala.concurrent.Future
import redis.protocol.MultiBulk
import akka.actor.ActorRef
import akka.event.Logging
import redis.commands.TransactionBuilder

class HipacheActor(config: Hipache.ServerConfig) extends Actor {
  val log = Logging(context.system, this)

  implicit val system = context.system

  import system.dispatcher

  lazy val client = RedisClient(
      config.server.host,
      config.server.port,
      config.server.password,
      config.server.db)

  val receive: Receive = {
    case HipacheActor.All       => all.thenReplyTo(sender)
    case HipacheActor.Get(f)    => get(f).thenReplyTo(sender)
    case HipacheActor.Put(f, b) => put(f,b).thenReplyTo(sender)
    case HipacheActor.Delete(f) => delete(f).thenReplyTo(sender)
  }

  import scala.reflect.runtime.universe.TypeTag
  implicit class ReplyHelper[A: TypeTag](f: Future[A]) {
    def thenReplyTo(to: ActorRef): Unit =
      f.onComplete {
        case scala.util.Success(x) => to ! HipacheActor.OK(x)
        case scala.util.Failure(e) => to ! HipacheActor.Failed(e.getMessage)
      }
  }

  def all: Future[Map[Hipache.Frontend,Hipache.Backend]] =
    for {
      keys <- client.smembers[String](indexKey)
      results: Seq[Option[(Hipache.Frontend, Hipache.Backend)]] <-
        Future.sequence(keys.map { k =>
          client.lrange[String](k, 0, -1).map { v =>
            (k, v) match {
              case Entry(frontend, backend) => Some((frontend, backend))
              case _ => None
            }
          }
        })
      map = results.flatten.toMap
    } yield map

  def get(frontend: Hipache.Frontend): Future[Option[Hipache.Backend]] =
    for {
      value <- client.lrange[String](keyFor(frontend), 0, -1)
      backend = (keyFor(frontend), value) match {
        case Entry(`frontend`, backend) => Some(backend)
        case _ => None
      }
    } yield backend

  def put(
      frontend: Hipache.Frontend,
      backend: Hipache.Backend): Future[Unit] = inTransaction { t =>
    val k = keyFor(frontend)
    t.watch(k)
    t.sadd(indexKey, k)
    t.del(k)
    t.rpush(k, frontend.name, backend.toString)
  }

  def delete(frontend: Hipache.Frontend): Future[Unit] = inTransaction { t =>
    val k = keyFor(frontend)
    t.srem(indexKey, k)
    t.del(k)
  }

  lazy val indexKey = s"${config.prefix}frontends"
  lazy val keyPrefix = s"${config.prefix}frontend:"
  def keyFor(frontend: Hipache.Frontend) = keyPrefix + frontend.domain

  override def postStop = {
    client.stop
  }

  def inTransaction[A](f: TransactionBuilder => Future[A]): Future[A] = {
    val t = client.transaction()
    val v = f(t)
    t.exec().flatMap(_ => v)
  }



  object Entry {
    lazy val reBackend = """(\w+)://(.+):(\d+)""".r

    type HipachePair = (Hipache.Frontend, Hipache.Backend)

    def unapply(kvPair: (String, Seq[String])): Option[HipachePair] =
      kvPair match {
        case (k: String, Seq(name: String, backendStr: String))
            if k.startsWith(keyPrefix) =>
          val frontend = Hipache.Frontend(
              name,
              k.stripPrefix(keyPrefix))
          backendStr match {
            case reBackend(scheme, host, portStr) =>
              val backend =
                Hipache.Backend(host, Integer.parseInt(portStr), scheme)
              Some((frontend, backend))
            case _ => None
          }
        case _ =>
          None
      }
  }

  implicit protected def toFUnit[A](f: Future[A]): Future[Unit] = f.map(_ => ())

}

object HipacheActor {

  sealed trait OpRequest
  object All extends OpRequest
  case class Get(
      frontend: Hipache.Frontend) extends OpRequest
  case class Put(
      frontend: Hipache.Frontend,
      backend: Hipache.Backend) extends OpRequest
  case class Delete(
      frontend: Hipache.Frontend) extends OpRequest

  import scala.reflect.runtime.universe._

  sealed trait OpReply
  case class OK[T: TypeTag](val value: T) extends OpReply
  case class Failed(msg: String) extends OpReply


}
