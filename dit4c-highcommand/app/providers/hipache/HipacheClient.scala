package providers.hipache

import akka.actor.Actor

import scala.concurrent.Future
import scala.util._
import akka.actor.ActorRef
import akka.event.Logging
import akka.actor.ActorSystem
import net.nikore.etcd._

class HipacheClient(config: Hipache.ServerConfig)(implicit system: ActorSystem) {
  import scala.language.implicitConversions
  import play.api.libs.json._
  import play.api.libs.functional.syntax._
  import EtcdJsonProtocol._
  import system.dispatcher

  val log = akka.event.Logging.getLogger(system, this.getClass)

  def all: Future[Map[Hipache.Frontend,Hipache.Backend]] =
    client.listDir(config.prefix) map { etcdListResponse =>
      etcdListResponse.node.nodes.getOrElse(Nil).flatMap {
        case NodeListElement(key, _, Some(value), _) =>
          (key -> Json.parse(value).as[Seq[String]]) match {
            case Entry(frontend, backend) => Some(frontend -> backend)
            case _ => None
          }
        case _ =>
          None
      }.toMap
    }

  def get(frontend: Hipache.Frontend): Future[Option[Hipache.Backend]] =
    client.getKey(keyFor(frontend))
      .map { etcdResponse =>
        etcdResponse.node.value.flatMap { value => 
          (keyFor(frontend) -> Json.parse(value).as[Seq[String]]) match {
            case Entry(`frontend`, backend) => Some(backend)
            case _ => None
          }
        }
      }.recover {
        case e: EtcdExceptions.KeyNotFoundException => None
      }

  def put(
      frontend: Hipache.Frontend,
      backend: Hipache.Backend): Future[Unit] = 
   client.setKey(
       keyFor(frontend), 
       Json.stringify(Json.arr(frontend.name, backend.toString))).map { _ =>
         log.info(s"Set Hipache mapping: ${frontend.name} → ${backend}")
       }

  def delete(frontend: Hipache.Frontend): Future[Unit] =
    client.deleteKey(keyFor(frontend)) map { _ =>
      log.info(s"Removed Hipache mapping: ${frontend.name}")
    }

  lazy val keyPrefix = s"${config.prefix.stripPrefix("/")}/frontend:"
  def keyFor(frontend: Hipache.Frontend) = keyPrefix + frontend.domain

  protected lazy val client = config.client

  object Entry {
    lazy val reBackend = """(\w+)://(.+):(\d+)""".r

    type HipachePair = (Hipache.Frontend, Hipache.Backend)

    def unapply(kvPair: (String, Seq[String])): Option[HipachePair] =
      kvPair match {
        case (k: String, Seq(name: String, backendStr: String))
            if k.startsWith("/"+keyPrefix) =>
          val frontend = Hipache.Frontend(
              name,
              k.stripPrefix("/"+keyPrefix))
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

