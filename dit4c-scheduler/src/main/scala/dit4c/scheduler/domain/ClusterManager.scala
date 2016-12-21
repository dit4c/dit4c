package dit4c.scheduler.domain

object ClusterManager {

  trait Command extends BaseCommand
  case object GetStatus extends Command

  trait Response extends BaseResponse
  trait GetStatusResponse extends Response

}

trait ClusterManager {
  def persistenceId: String
}