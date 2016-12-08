package utils.akka

import akka.actor._

trait ActorHelpers extends Actor {

  val emptyReceive: Receive = PartialFunction.empty[Any, Unit]

  def sealedReceive[T](f: T => Unit)(implicit ev: Manifest[T]): Receive = {
    case msg: T => f(msg)
  }


}