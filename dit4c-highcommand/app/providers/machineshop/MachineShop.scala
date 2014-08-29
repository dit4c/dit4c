package providers.machineshop

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
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
      response <- WS.url(s"$managementUrl$path").get
    } yield response.body.trim
  }

}