package eu.kohout

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}

object Main{
  val actorSystem = ActorSystem("application")

  def main(args: Array[String]): Unit = {

    val rootActor = clusterSingleton

  }

  private def clusterSingleton: ActorRef = {
    actorSystem
      .actorOf(
        ClusterSingletonManager
          .props(
            singletonProps = RootActor.props,
            terminationMessage = PoisonPill,
            settings = ClusterSingletonManagerSettings(actorSystem)
          ),
        name = RootActor.name
      )
  }
}
