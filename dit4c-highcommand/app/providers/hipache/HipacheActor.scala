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

  val client = new HipacheClient(config)

  val receive: Receive = {
    case HipacheActor.All       => client.all.thenReplyTo(sender)
    case HipacheActor.Get(f)    => client.get(f).thenReplyTo(sender)
    case HipacheActor.Put(f, b) => client.put(f,b).thenReplyTo(sender)
    case HipacheActor.Delete(f) => client.delete(f).thenReplyTo(sender)
  }

  import scala.reflect.runtime.universe.TypeTag
  implicit class ReplyHelper[A: TypeTag](f: Future[A]) {
    def thenReplyTo(to: ActorRef): Unit =
      f.onComplete {
        case scala.util.Success(x) => to ! HipacheActor.OK(x)
        case scala.util.Failure(e) => to ! HipacheActor.Failed(e.getMessage)
      }
  }

  override def postStop = {
    client.disconnect
  }

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
