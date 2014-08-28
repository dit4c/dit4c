package providers.machineshop

import scala.concurrent.Future

object MachineShop {
  trait Container {
    def name: String
    def active: Boolean

    def start: Future[Container]
    def stop: Future[Container]
    def delete: Future[Unit]
  }
}