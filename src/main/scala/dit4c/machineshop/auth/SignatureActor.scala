package dit4c.machineshop.auth

import java.io.FileInputStream
import java.io.File
import java.text.ParseException
import akka.actor.Actor
import scala.util.Try
import akka.event.Logging
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import akka.actor.ActorRef
import akka.actor.Cancellable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{future, Future}
import com.nimbusds.jose.jwk.JWKSet
import scala.collection.JavaConversions._
import spray.http.HttpRequest

class SignatureActor(publicKeySource: java.net.URI, keyUpdateInterval: FiniteDuration)
    extends Actor {
  val log = Logging(context.system, this)

  implicit val executionContext = context.system.dispatcher
  implicit val actorRefFactory = context.system

  import SignatureActor._

  type QueuedCheck = (ActorRef, AuthCheck)

  /* Signature Checker Updates */
  object UpdateSignatureVerifier
  case class ReplaceSignatureVerifier(sc: SignatureVerifier)
  val base: Receive = {
    case UpdateSignatureVerifier =>
      createVerifier.foreach { sc =>
        context.self ! ReplaceSignatureVerifier(sc)
      }
  }

  var scheduledCheck: Option[Cancellable] = None

  override def preStart = {
    // Schedule updates
    scheduledCheck = Some(
      context.system.scheduler.schedule(
        Duration.Zero,
        keyUpdateInterval,
        context.self,
        UpdateSignatureVerifier))
  }

  override def postStop = {
    scheduledCheck.foreach(_.cancel)
  }

  val receive: Receive = queueChecks(Nil)

  def queueChecks(queue: Seq[QueuedCheck]): Receive = base.orElse {
    // Queue checks, successively altering state to add to the queue
    case check: AuthCheck =>
      context.become(queueChecks(queue :+ (sender, check)))
    // When we eventually get a replacement, dequeue and switch to new state
    case ReplaceSignatureVerifier(sv) =>
      transition(sv, queue)
  }

  def performChecks(checker: SignatureVerifier): Receive = base.orElse {
    // Perform the check immediately
    case query: AuthCheck =>
      sender ! checker(query.request)
    // Replace checker
    case ReplaceSignatureVerifier(sv) =>
      transition(sv, Nil)
  }

  private def transition(checker: SignatureVerifier, queue: Seq[QueuedCheck]) {
    context.become(performChecks(checker))
    queue.foreach { case (qSender, qCheck) =>
      qSender ! checker(qCheck.request)
    }
  }

  import spray.http._
  import spray.client.pipelining._

  private def createVerifier: Future[SignatureVerifier] = {
    log.info(s"Retrieving keys from $publicKeySource")
    if (publicKeySource.isAbsolute()) {
      pipeline(Get(publicKeySource.toASCIIString)).map { content =>
        new SignatureVerifier(JWKSet.parse(content))
      }
    } else {
      // It's a file, so fetch directly
      future {
        try {
          val fileInput = new FileInputStream(publicKeySource.getPath())
          val content = scala.io.Source.fromInputStream(fileInput).mkString
          new SignatureVerifier(JWKSet.parse(content))
        } catch {
          case e: ParseException =>
            log.warning(
                s"No keys loaded. Unable to read public keys: ${e.getMessage}")
            new SignatureVerifier(new JWKSet())
        }
      }
    }
  }

  protected def pipeline = sendReceive ~> unmarshal[String]

}


object SignatureActor {

  case class AuthCheck(val request: HttpRequest)

  sealed trait AuthResponse
  object AccessGranted extends AuthResponse
  case class AccessDenied(reason: String) extends AuthResponse
}
