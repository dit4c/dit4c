package providers.db

import scala.concurrent.{ExecutionContext, Future, future}
import play.api.libs.ws.WS
import play.api.mvc.Results.EmptyContent
import play.api.libs.json._

object CouchDB {

  abstract class Instance(implicit ec: ExecutionContext) {

    protected def url: java.net.URL

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
  }

  class Database(val name: String)(implicit ec: ExecutionContext) {

  }

}