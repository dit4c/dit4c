package domain

import com.softwaremill.tagging._
import akka.actor._
import org.bouncycastle.openpgp.PGPPublicKey
import java.time.Instant
import dit4c.protobuf.tokens.ClusterAccessPass
import scala.util.Try
import java.security.MessageDigest
import akka.util.ByteString
import scala.util.Success
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.apache.commons.lang3.CharUtils
import java.util.Base64

object AccessPassManager {

  sealed trait Command extends BaseCommand
  case class RegisterAccessPass(signedData: ByteString) extends Command
  case class Envelope(accessPassId: String, cmd: AccessPass.Command) extends Command

}


class AccessPassManager extends Actor with ActorLogging {
  import AccessPassManager._

  val scheduler = context.parent.taggedWith[SchedulerAggregate]
  val schedulerId = context.parent.path.name

  val receive: Receive = {
    case cmd: Command => cmd match {
      case RegisterAccessPass(signedData: ByteString) =>
        import AccessPass._
        child(calculateId(signedData)) forward Register(signedData)
      case Envelope(id, cmd) =>
        child(id) forward cmd
    }
  }

  def child(name: String) =
    context.child(name).getOrElse {
      context.actorOf(AccessPass.props(scheduler), name)
    }

}