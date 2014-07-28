package providers.hipache

import akka.actor.Actor
import redis.RedisServer
import redis.RedisClient
import scala.concurrent.Future
import redis.protocol.MultiBulk
import akka.actor.ActorRef

class HipacheActor(config: Hipache.ServerConfig) extends Actor {

  implicit val system = context.system

  import system.dispatcher

  lazy val client = RedisClient(
      config.server.host,
      config.server.port,
      config.server.password,
      config.server.db)

  val receive: Receive = {
    case HipacheActor.Put(f, b) => put(f,b).thenReplyTo(sender)
    case HipacheActor.Delete(f) => delete(f).thenReplyTo(sender)
  }

  implicit class ReplyHelper[A](f: Future[A]) {
    def thenReplyTo(to: ActorRef): Unit =
      f.onComplete {
        case scala.util.Success(_) => to ! HipacheActor.OK
        case scala.util.Failure(e) => to ! HipacheActor.Failed(e.getMessage)
      }
  }

  def put(
      frontend: Hipache.Frontend,
      backend: Hipache.Backend): Future[MultiBulk] = {
    val k = keyFor(frontend)
    val t = client.transaction()
    t.watch(k)
    val del = t.del(k)
    val set = t.rpush(k, frontend.name, backend.toString)
    t.exec()
  }

  def delete(frontend: Hipache.Frontend): Future[Long] =
    client.del(keyFor(frontend))

  def keyFor(frontend: Hipache.Frontend) =
    s"${config.prefix}frontend:${frontend.domain}"

  override def postStop = {
    client.stop
  }
}

object HipacheActor {

  sealed trait OpRequest
  case class Put(
      frontend: Hipache.Frontend,
      backend: Hipache.Backend) extends OpRequest
  case class Delete(
      frontend: Hipache.Frontend) extends OpRequest

  sealed trait OpReply
  object OK extends OpReply
  case class Failed(msg: String) extends OpReply


}
