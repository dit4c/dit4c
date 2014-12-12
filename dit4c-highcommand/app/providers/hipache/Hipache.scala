package providers.hipache

import net.nikore.etcd.EtcdClient
import play.api.libs.json.Format
import play.api.libs.json.JsPath

object Hipache {

  case class ServerConfig(
      client: EtcdClient,
      prefix: String)

  case class Frontend(
      name: String,
      domain: String)

  case class Backend(
      host: String,
      port: Int = 80,
      scheme: String = "http") {
    override def toString = s"$scheme://$host:$port"
  }

  implicit val hipacheBackendFormat: Format[Hipache.Backend] = {
    import play.api.libs.functional.syntax._
    (
      (JsPath \ "host").format[String] and
      (JsPath \ "port").format[Int] and
      (JsPath \ "scheme").format[String]
    )(Hipache.Backend.apply _, unlift(Hipache.Backend.unapply))
  }


}