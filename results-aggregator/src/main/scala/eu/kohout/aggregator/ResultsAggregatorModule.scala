package eu.kohout.aggregator
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.{AbstractModule, Provides, Singleton}
import com.typesafe.config.Config
import eu.kohout.tags.ResultsAggregatorTag

class ResultsAggregatorModule extends AbstractModule {

  @Provides
  @Singleton
  @ResultsAggregatorTag
  def resultsAggregator(
    actorSystem: ActorSystem,
    config: Config
  ): ActorRef =
    ResultsAggregator.asClusterSingleton(ResultsAggregator.props, config, actorSystem)
}
