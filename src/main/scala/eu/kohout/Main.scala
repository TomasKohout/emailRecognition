package eu.kohout

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.cluster.singleton.{
  ClusterSingletonManager,
  ClusterSingletonManagerSettings,
  ClusterSingletonProxy,
  ClusterSingletonProxySettings
}
import com.typesafe.config.{Config, ConfigFactory}
import eu.kohout.rest.{HttpServer, HttpServerHandler}
import akka.pattern.ask
import com.typesafe.scalalogging.Logger

import concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object Main {
  val actorSystem = ActorSystem("application")
  val config: Config = ConfigFactory.load()
  val log = Logger(getClass)
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  def main(args: Array[String]): Unit = {

    val rootActor = clusterSingleton

    val rootActorProxy = actorSystem.actorOf(
      ClusterSingletonProxy
        .props(rootActor.path.toStringWithoutAddress, ClusterSingletonProxySettings(actorSystem)),
      RootActor.name + "ServerProxy"
    )

    val httpServer =
      new HttpServer(config, HttpServerHandler(rootActorProxy)(5 seconds))(actorSystem).start

  }

  private def clusterSingleton: ActorRef =
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
