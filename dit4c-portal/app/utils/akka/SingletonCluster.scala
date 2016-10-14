package utils.akka

import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.ExtendedActorSystem
import akka.actor.ActorSystem
import com.typesafe.config.Config
import akka.cluster.Cluster

class SingletonClusterImpl(system: ExtendedActorSystem) extends Extension {

  // Self-join cluster of 1
  Cluster(system).join(Cluster(system).selfAddress)

}

object SingletonCluster extends ExtensionId[SingletonClusterImpl] with ExtensionIdProvider {

  override def lookup = SingletonCluster

  override def createExtension(system: ExtendedActorSystem) =
    new SingletonClusterImpl(system)
}