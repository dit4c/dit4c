package providers.hipache

import akka.actor.Actor
import redis.RedisServer
import redis.RedisClient
import scala.concurrent.Future
import redis.protocol.MultiBulk
import akka.actor.ActorRef
import akka.event.Logging
import redis.commands.TransactionBuilder
import akka.actor.ActorSystem

class HipacheClient(config: Hipache.ServerConfig)(implicit system: ActorSystem) {

  import system.dispatcher

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

  def disconnect = {
    client.stop
  }

  protected lazy val client = RedisClient(
      config.server.host,
      config.server.port,
      config.server.password,
      config.server.db)

  type TransactionOp[A] = TransactionBuilder => Future[A]

  protected def inTransaction[A](f: TransactionOp[A]): Future[A] = {
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

