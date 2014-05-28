package models

import com.google.inject._
import scala.concurrent._

trait ComputeNodeProjectHelper {
  type ComputeNodeProjectCreator = Project => Future[ComputeNode.Project]
  type ComputeNodeProjectResolver = Project => Future[Option[ComputeNode.Project]]
  def creator: ComputeNodeProjectCreator
  def resolver: ComputeNodeProjectResolver
}

class ComputeNodeProjectHelperImpl @Inject() (dao: ComputeNodeDAO)
  (implicit ec: ExecutionContext)
  extends ComputeNodeProjectHelper {

  override def creator = { project: Project =>
    for {
      nodes <- dao.list
      node = nodes.head
      p <- node.projects.create(project.name)
    } yield p
  }

  override def resolver = {
    lazy val bulkResolver: Future[String => Option[ComputeNode.Project]] = {
      dao.list
        .flatMap(nodes => Future.sequence(nodes.map(_.projects.list)))
        .map(_.flatten.toList.sortBy(_.name))
        .map(_.map(cnp => (cnp.name -> cnp)).toMap.get _)
    }
    { project: Project => bulkResolver.map(f => f(project.name)) }
  }


}