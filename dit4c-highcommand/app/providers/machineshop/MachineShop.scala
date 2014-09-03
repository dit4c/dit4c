package providers.machineshop

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.util.Try
import play.api.libs.ws.WS

object MachineShop {
  trait Container {
    def name: String
    def active: Boolean

    def start: Future[Container]
    def stop: Future[Container]
    def delete: Future[Unit]
  }

  def fetchServerId(
      managementUrl: String
      )(implicit executionContext: ExecutionContext): Future[String] = {
    import play.api.Play.current
    val path = "server-id"
    for {
      call <- Future.fromTry(Try(WS.url(s"$managementUrl$path")))
      response <- call.get
    } yield response.body.trim
  }

}