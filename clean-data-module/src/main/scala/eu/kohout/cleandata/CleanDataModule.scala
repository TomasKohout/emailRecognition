package eu.kohout.cleandata
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.{AbstractModule, Provides, Singleton}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.ModelManagerTag

class CleanDataModule extends AbstractModule {

  private val log = Logger(getClass)

  @Provides
  @Singleton
  @CleanDataManagerTag
  def cleanDataManager(
    config: Config,
    @ModelManagerTag modelManager: ActorRef,
    actorSystem: ActorSystem
  ): ActorRef = {
    log.info("Creating CleanDataMaanger")
    CleanDataManager.asClusterSingleton(CleanDataManager.props(config = config, modelManager = modelManager), config, actorSystem)
  }
}
