package eu.kohout.cleandata
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.{AbstractModule, Provides, Singleton}
import com.typesafe.config.Config
import eu.kohout.model.manager.ModelManagerTag

class CleanDataModule extends AbstractModule {

  @Provides
  @Singleton
  @CleanDataManagerTag
  def cleanDataManager(
    config: Config,
    @ModelManagerTag modelManager: ActorRef,
    actorSystem: ActorSystem
  ): ActorRef =
    actorSystem.actorOf(CleanDataManager.props(config = config, modelManager = modelManager), CleanDataManager.name)
}
