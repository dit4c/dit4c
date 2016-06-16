package dit4c.scheduler.domain

object ClusterManager {

  trait Command
  case object GetStatus extends Command

}

trait ClusterManager {
  def persistenceId: String

  object InstancePersistenceId extends ChildPersistenceId("Instance")

  abstract class ChildPersistenceId(childTypeId: String) {
    type ChildId = String
    val separator = "-"

    def apply(childId: ChildId) =
      Seq(persistenceId, childTypeId, childId).mkString(separator)

    def unapply(childPersistenceId: String): Option[ChildId] = {
      val prefix =
        Seq(persistenceId, childTypeId).mkString(separator) + separator
      Some(childPersistenceId)
        .filter(_.startsWith(prefix))
        .map(_.stripPrefix(prefix))
    }
  }
}