package providers.db

import akka.actor.ActorSystem
import akka.util.Timeout
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import play.api.Application
import play.api.libs.concurrent.Akka
import play.api.libs.ws.WS
import play.api.mvc.Results.EmptyContent
import play.api.libs.json._
import gnieh.sohva.async.CouchClient

object CouchDB {

  abstract class Instance(implicit ec: ExecutionContext, app: Application) {

    implicit private val timeout: Timeout = Timeout(5.seconds)
    implicit private def system: ActorSystem = Akka.system(app)
    implicit private val instance = this

    def url: java.net.URL

    val client = new CouchClient(
        host = url.getHost,
        port = url.getPort,
        ssl = url.getProtocol == "https")

    object databases {

      def create(name: String): Future[Database] = {
        val holder = WS.url(s"${url}$name")
        holder.put(EmptyContent()).map { response =>
          response.status match {
            case 201 => new Database(name)
          }
        }
      }

      def list: Future[Seq[Database]] = {
        val holder = WS.url(s"${url}_all_dbs")
        holder.get.map { response =>
          response.json.asInstanceOf[JsArray].value
            .map(_.as[String])
            .map(new Database(_))
        }
      }

      def get(name: String): Future[Option[Database]] =
        list.map(_.find(_.name == name))

      // Aliases
      def apply() = list
      def apply(name: String) = get(name)
    }

    def newID = client._uuid

    def disconnect {
      client.shutdown
    }

  }

  class Database(val name: String)(implicit ec: ExecutionContext, instance: CouchDB.Instance) {

    val baseURL = new java.net.URL(s"${instance.url}$name")

    def newID: Future[String] = instance.newID

  }

}