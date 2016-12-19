package dit4c.scheduler.domain

import akka.actor._

object Cluster {

  def props(clusterInfo: Option[ClusterInfo], defaultConfigProvider: ConfigProvider): Props =
    Props(classOf[Cluster], clusterInfo, defaultConfigProvider)

  trait Command extends BaseCommand
  case object GetState extends Command

  trait Response extends BaseResponse
  trait GetStateResponse extends Response
  case object Uninitialized extends GetStateResponse
  case class Inactive(
      id: String,
      displayName: String) extends GetStateResponse
  case class Active(
      id: String,
      displayName: String,
      supportsSave: Boolean) extends GetStateResponse

}

class Cluster(
    clusterInfo: Option[ClusterInfo],
    defaultConfigProvider: ConfigProvider)
    extends Actor
    with ActorLogging {
  import Cluster._

  lazy val clusterId = self.path.name

  override def receive: Receive = clusterInfo match {
    case None =>
      {
        case GetState => sender ! Uninitialized
      }
    case Some(info) if !info.active =>
      {
        case GetState =>
          sender ! Inactive(
            clusterId,
            info.displayName)
      }
    case Some(info) =>
      {
        case GetState =>
          sender ! Active(
            clusterId,
            info.displayName,
            info.supportsSave)
        case msg =>
          clusterManager(info) forward msg
      }
  }

  protected def clusterManager(clusterInfo: ClusterInfo) =
    context.child(managerActorId) match {
      case None =>
        val manager: ActorRef =
          context.actorOf(
              managerProps,
              managerActorId)
        context.watch(manager)
        manager
      case Some(ref) => ref
    }

  private val managerActorId = "manager"

  private val managerProps =
    RktClusterManager.props(
        clusterId, defaultConfigProvider.rktRunnerConfig)(context.dispatcher)

}