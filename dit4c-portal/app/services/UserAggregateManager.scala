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

object UserAggregateManager {

  sealed trait Command
  case class UserEnvelope(userId: String, msg: Any) extends Command

}

class UserAggregateManager(
    val instanceAggregateManager: ActorRef @@ InstanceAggregateManager)
    extends Actor with ActorLogging {
  import UserAggregateManager._
  import services.InstanceAggregateManager
  import domain.ClusterAggregate
  import akka.pattern.{ask, pipe}
  import context.dispatcher

  val receive: Receive = LoggingReceive {
    case UserEnvelope(userId, msg) =>
      userRef(userId) forward msg
  }

  def userRef(userId: String) = {
    context.child(aggregateId(userId)).getOrElse {
      val agg = context.actorOf(
          aggregateProps(userId), aggregateId(userId))
      context.watch(agg)
      agg
    }
  }

  private def aggregateId(userId: String) = s"User-$userId"

  private def aggregateProps(userId: String): Props =
    Props(classOf[UserAggregate], userId, instanceAggregateManager)


}