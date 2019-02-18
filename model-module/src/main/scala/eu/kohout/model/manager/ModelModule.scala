package eu.kohout.model.manager
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.{AbstractModule, Provides, Singleton}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

class ModelModule extends AbstractModule {

  @Provides
  @Singleton
  @ModelManagerTag
  def modelManager(
    config: Config,
    actorSystem: ActorSystem
  ): ActorRef =
    ModelManager.asClusterSingleton(ModelManager.props(config), config, actorSystem)
}
