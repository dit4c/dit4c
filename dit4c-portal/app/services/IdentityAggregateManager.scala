package services

import akka.actor._
import com.softwaremill.tagging._
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import scala.concurrent.duration._
import domain.InstanceAggregate
import domain.InstanceAggregate.RecordInstanceStart
import sun.security.jca.GetInstance
import akka.event.LoggingReceive
import domain.UserAggregate
import java.util.Base64
import domain.IdentityAggregate

object IdentityAggregateManager {

  sealed trait Command

  case class IdentityEnvelope(identityKey: String, msg: Any) extends Command

}

class IdentityAggregateManager(
    val userAggregateManager: ActorRef @@ UserAggregateManager)
    extends Actor with ActorLogging {
  import IdentityAggregateManager._
  import services.InstanceAggregateManager
  import context.dispatcher

  val receive: Receive = LoggingReceive {
    case IdentityEnvelope(identityKey, msg) =>
      identityRef(identityKey) forward msg
  }

  def identityRef(identityKey: String) = {
    context.child(aggregateId(identityKey)).getOrElse {
      val agg = context.actorOf(
          aggregateProps(identityKey), aggregateId(identityKey))
      context.watch(agg)
      agg
    }
  }

  private def aggregateId(identityKey: String) = s"Identity-${base64Url(identityKey)}"

  private def base64Url(s: String) = Base64.getUrlEncoder.encodeToString(s.getBytes("utf8"))

  private def aggregateProps(identityKey: String): Props =
    Props(classOf[IdentityAggregate], identityKey, userAggregateManager)


}