package models

import com.google.inject._
import scala.concurrent._

trait ComputeNodeContainerHelper {
  type ComputeNodeContainerCreator = Container => Future[ComputeNode.Container]
  type ComputeNodeContainerResolver = Container => Future[Option[ComputeNode.Container]]
  def creator: ComputeNodeContainerCreator
  def resolver: ComputeNodeContainerResolver
}

class ComputeNodeContainerHelperImpl @Inject() (dao: ComputeNodeDAO)
  (implicit ec: ExecutionContext)
  extends ComputeNodeContainerHelper {

  override def creator = { container: Container =>
    for {
      nodes <- dao.list
      node = nodes.head
      c <- node.containers.create(container.name, container.image)
    } yield c
  }

  override def resolver = {
    lazy val bulkResolver: Future[String => Option[ComputeNode.Container]] = {
      dao.list
        .flatMap(nodes => Future.sequence(nodes.map(_.containers.list)))
        .map(_.flatten.toList.sortBy(_.name))
        .map(_.map(cnp => (cnp.name -> cnp)).toMap.get _)
    }
    { container: Container => bulkResolver.map(f => f(container.name)) }
  }


}