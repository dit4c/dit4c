package providers.db

import akka.actor.ActorSystem
import akka.util.Timeout
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import play.api.Application
import play.api.libs.concurrent.Akka
import play.api.mvc.Results.EmptyContent
import play.api.libs.json._
import gnieh.sohva.SohvaSerializer
import gnieh.sohva.async.CouchClient
import net.liftweb.{json => lift}

object CouchDB {

  abstract class Instance(implicit ec: ExecutionContext, system: ActorSystem) {

    implicit private val timeout: Timeout = Timeout(5.seconds)
    implicit private val instance = this

    def url: java.net.URL

    lazy val client = new CouchClient(
        host = url.getHost,
        port = url.getPort,
        ssl = url.getProtocol == "https",
        custom = List(allVersions(playJsValueSerializer)))

    object databases {

      def create(name: String): Future[Database] = 
        client.database(name).create.map { _ =>
          new Database(name)
        }

      def list: Future[Seq[Database]] =
        client._all_dbs.map { databaseNames =>
          databaseNames.map(name => new Database(name))
        }

      def get(name: String): Future[Option[Database]] =
        client.database(name).exists.map {
          case true => Some(new Database(name))
          case false => None
        }

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

    def newID: Future[String] = instance.newID

    def asSohvaDb = instance.client.database(name)

  }

  def allVersions[T](s: lift.CustomSerializer[T]): SohvaSerializer[T] =
    new SohvaSerializer[T] { override def serializer(version: String) = s }

  val playJsValueSerializer = new lift.CustomSerializer[JsValue]({ (formats) =>
    import scala.language.implicitConversions
    import net.liftweb.json.JValue
    (
      { case v: JValue => Json.parse(lift.compact(lift.render(v))) },
      { case v: JsValue => lift.parse(Json.stringify(v)) }
    )
  })

}