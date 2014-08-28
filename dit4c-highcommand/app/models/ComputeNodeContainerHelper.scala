package models

import com.google.inject._
import scala.concurrent._
import scala.util.{Try, Success, Failure}
import providers.machineshop.ContainerProvider
import providers.machineshop.MachineShop

trait ComputeNodeContainerHelper {
  type ComputeNodeContainerCreator = Container => Future[MachineShop.Container]
  type ComputeNodeContainerResolver = Container => Future[Option[MachineShop.Container]]
  def creator: ComputeNodeContainerCreator
  def resolver: ComputeNodeContainerResolver
}

class ComputeNodeContainerHelperImpl @Inject() (dao: ComputeNodeDAO)
  (implicit ec: ExecutionContext)
  extends ComputeNodeContainerHelper {

  override def creator = { container: Container =>
    for {
      node <- dao.get(container.computeNodeId)
      c <- node.get.containers.create(container.name, container.image)
    } yield c
  }

  override def resolver = { container: Container =>
    for {
      node <- dao.get(container.computeNodeId)
      c <- node.get.containers.get(container.name)
    } yield c
  }


  // Get list or return empty list
  private def nodeContainers(node: ComputeNode) =
    node.containers.list.fallbackTo(Future.successful(Nil))


}