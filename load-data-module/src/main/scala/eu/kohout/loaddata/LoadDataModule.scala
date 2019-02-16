package eu.kohout.loaddata
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.{AbstractModule, Provides, Singleton}
import com.typesafe.config.Config
import eu.kohout.cleandata.CleanDataManagerTag

class LoadDataModule extends AbstractModule {

  @Provides
  @Singleton
  @LoadDataManagerTag
  def loadDataManager(
    config: Config,
    actorSystem: ActorSystem,
    @CleanDataManagerTag cleanDataManager: ActorRef
  ): ActorRef = actorSystem.actorOf(LoadDataManager.props(config, cleanDataManager))
}
