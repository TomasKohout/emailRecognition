package eu.kohout
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import eu.kohout.rest.HttpServer

object Main{
  val actorSystem = ActorSystem("application")

  def main(args: Array[String]): Unit = {

    val rootActor = clusterSingleton

    rootActor ! RootActor.StartApplication

  }

  private def clusterSingleton: ActorRef = {
    val singleton = actorSystem
      .actorOf(
        ClusterSingletonManager
          .props(
            singletonProps = RootActor.props,
            terminationMessage = PoisonPill,
            settings = ClusterSingletonManagerSettings(actorSystem)
          ),
        name = RootActor.name
      )

    actorSystem.actorOf(
      ClusterSingletonProxy
        .props(
          singletonManagerPath = singleton.path.toStringWithoutAddress,
          settings = ClusterSingletonProxySettings(actorSystem)
        ),
      name = RootActor.name + "Proxy"
    )
  }
}
