package dit4c.scheduler.service

import akka.actor._
import akka.util.ByteString
import dit4c.scheduler.api._

class SignedMessageProcessor(
    keyManager: ActorRef,
    signedMessage: String) extends Actor with ActorLogging {
  import dit4c.common.KeyHelpers._

  override def preStart {
    keyManager ! KeyManager.GetPublicKeyInfo
  }

  val receive: Receive = {
    case KeyManager.PublicKeyInfo(keyFingerprint, armoredPgpPublicKeyBlock) =>
      val pkr = parseArmoredPublicKeyRing(armoredPgpPublicKeyBlock).right.get
      verifyData(ByteString(signedMessage.getBytes), pkr.signingKeys) match {
        case Left(msg) =>
        case Right((_, sigs)) if sigs.isEmpty =>
          log.error("Message not signed with a current signing key")
          context.stop(context.self)
        case Right((data, _)) =>
          try {
            import ApiMessage.Payload
            ApiMessage.parseFrom(data.toArray).payload match {
              case Payload.Empty => // Do nothing
              case Payload.AddNode(msg) =>
                context.parent ! msg
            }
          } catch {
            case e: Throwable =>
              log.error(e, "Unable to read signed payload")
          }
      }
  }



}