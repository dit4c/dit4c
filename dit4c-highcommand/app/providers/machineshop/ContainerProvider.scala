package providers.machineshop

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import play.api.libs.json._
import play.api.libs.ws._
import scala.util.Try
import com.nimbusds.jose.jwk.RSAKey
import akka.util.ByteString

class ContainerProvider(
    managementUrl: String,
    privateKeyProvider: () => Future[RSAKey]
  )(implicit executionContext: ExecutionContext) {

  import play.api.libs.functional.syntax._
  import play.api.Play.current
  import MachineShop.Client
  import MachineShop.Container

  val client = new Client(managementUrl, privateKeyProvider)

  def create(name: String, image: String) =
    client("containers")
      .signed { ws: WSRequest =>
        ws.withMethod("POST")
          .withHeaders("Content-Type" -> "application/json; charset=utf-8")
          .withBody(InMemoryBody(ByteString(Json.stringify(
              Json.obj("name" -> name, "image" -> image)).getBytes)))
      }
      .map(rethrowErrors(_.json.as[Container]))

  def get(name: String): Future[Option[Container]] =
    client(s"containers/$name")
      .unsigned(_.withMethod("GET"))
      .map(r => Try(r.json.as[Container]).toOption)

  def list: Future[Seq[Container]] =
    client("containers")
      .unsigned(_.withMethod("GET"))
      .map { response =>
        response.json.as[Seq[Container]]
      }

  class ContainerImpl(
      val name: String,
      val active: Boolean
      )(implicit executionContext: ExecutionContext) extends Container {

    override def start: Future[Container] =
      client(s"containers/$name/start")
        .signed(_.withMethod("POST"))
        .map(rethrowErrors(_.json.as[Container]))

    override def stop: Future[Container] =
      client(s"containers/$name/stop")
        .signed(_.withMethod("POST"))
        .map(rethrowErrors(_.json.as[Container]))

    override def delete: Future[Unit] =
      stop.flatMap { _ =>
        client(s"containers/$name")
          .signed(_.withMethod("DELETE"))
          .flatMap { response =>
            if (response.status == 204) Future.successful[Unit](Unit)
            else Future.failed(
                new Exception(s"Deletion failed: ${response.status}"))
          }
      }

    override def toString = s"ContainerProvider.Container($name, $active)"

  }

  implicit val containerReads: Reads[Container] = (
    (__ \ "name").read[String] and
    (__ \ "active").read[Boolean]
  )((new ContainerImpl(_,_)))

  private def rethrowErrors[A](f: WSResponse => A)(r: WSResponse) =
    Try(f(r)).getOrElse(throw MachineShop.Error(r.body))

}