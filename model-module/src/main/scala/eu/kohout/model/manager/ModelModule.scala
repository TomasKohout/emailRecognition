package eu.kohout.model.manager
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.{AbstractModule, Provides, Singleton}
import com.typesafe.config.Config

class ModelModule extends AbstractModule {

  @Provides
  @Singleton
  @ModelManagerTag
  def modelManager(
    config: Config,
    actorSystem: ActorSystem
  ): ActorRef = actorSystem.actorOf(ModelManager.props(config), ModelManager.name)
}
