package eu.kohout.model.manager
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.{AbstractModule, Provides, Singleton}
import com.typesafe.config.Config
import eu.kohout.tags.{ModelManagerTag, ResultsAggregatorTag}

class ModelModule extends AbstractModule {

  @Provides
  @Singleton
  @ModelManagerTag
  def modelManager(
    config: Config,
    actorSystem: ActorSystem,
    @ResultsAggregatorTag resultsAggregator: ActorRef
  ): ActorRef =
    ModelManager.asClusterSingleton(ModelManager.props(config, resultsAggregator), config, actorSystem)
}
