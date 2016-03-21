package dit4c.machineshop.auth

import java.io.FileInputStream
import java.text.ParseException
import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import com.nimbusds.jose.jwk.JWKSet
import akka.actor._
import akka.event.Logging
import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers._
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.client.TransformerAux._
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.settings.ClientConnectionSettings

class SignatureActor(publicKeySource: java.net.URI, keyUpdateInterval: FiniteDuration)
    extends Actor {

  import dit4c.common.AkkaHttpExtras._

  val log = Logging(context.system, this)
  implicit val executionContext = context.system.dispatcher
  implicit val actorRefFactory = context.system
  implicit val mat = ActorMaterializer()

  import SignatureActor._

  type QueuedCheck = (ActorRef, AuthCheck)

  /* Signature Checker Updates */
  case class UpdateSignatureVerifier(retryCount: Int = 0)
  case class ReplaceSignatureVerifier(sc: SignatureVerifier)
  val base: Receive = {
    case UpdateSignatureVerifier(retries) =>
      createVerifier.onComplete {
        case Success(sv) =>
          context.self ! ReplaceSignatureVerifier(sv)
        case Failure(e) =>
          val delay = 1.second * math.pow(2, retries).toInt
          log.warning(
              s"Failure generating signature verifier: $e\nRetry in $delay.")
          context.system.scheduler.scheduleOnce(
            delay,
            context.self,
            UpdateSignatureVerifier(retries + 1))
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
        UpdateSignatureVerifier()))
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

  def performChecks(checker: AuthCheck => AuthResponse): Receive = base.orElse {
    // Perform the check immediately
    case query: AuthCheck =>
      sender ! checker(query)
    // Replace checker
    case ReplaceSignatureVerifier(sv) =>
      transition(sv, Nil)
  }

  private def transition(sv: SignatureVerifier, queue: Seq[QueuedCheck]) {
    val f = { query: AuthCheck =>
      sv(query.request) match {
        case Right(()) => AccessGranted
        case Left(msg) => AccessDenied(msg)
      }
    }
    context.become(performChecks(f))
    queue.foreach { case (qSender, qCheck) =>
      qSender ! f(qCheck)
    }
  }

  private def createVerifier: Future[SignatureVerifier] = {
    log.info(s"Retrieving keys from $publicKeySource")
    if (publicKeySource.isAbsolute()) {
      pipeline(Get(publicKeySource.toASCIIString)).map { content =>
        val keySet = JWKSet.parse(content)
        log.info(s"Retrieved ${keySet.getKeys.size} keys from $publicKeySource")
        new SignatureVerifier(keySet)
      }
    } else {
      // It's a file, so fetch directly
      Future {
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

  protected def pipeline(req: HttpRequest) =
    for {
      res <- Http().singleResilientRequest(req,
          ClientConnectionSettings(context.system), None, log)
      str <- stringUnmarshaller(res.entity)
    } yield str
}


object SignatureActor {

  case class AuthCheck(val request: HttpRequest)

  sealed trait AuthResponse
  object AccessGranted extends AuthResponse
  case class AccessDenied(reason: String) extends AuthResponse
}
