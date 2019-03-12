package eu.kohout

import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, PoisonPill, Props, Stash}
import akka.cluster.singleton.{
  ClusterSingletonManager,
  ClusterSingletonManagerSettings,
  ClusterSingletonProxy,
  ClusterSingletonProxySettings
}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import eu.kohout.aggregator.ResultsAggregator
import eu.kohout.cleandata.CleanDataManager
import eu.kohout.dictionary.DictionaryResolver
import eu.kohout.loaddata.{LoadDataManager, LoadDataManagerLogic}
import eu.kohout.model.manager.{ModelManager, ModelMessages}
import smile.feature.Bag

object RootActor {
  val name = "RootActor"

  def clusterSingleton(
    props: Props,
    name: String
  )(
    implicit actorContext: ActorContext
  ): ActorRef = {

    val singleton = actorContext
      .actorOf(
        ClusterSingletonManager
          .props(
            singletonProps = props,
            terminationMessage = PoisonPill,
            settings = ClusterSingletonManagerSettings(actorContext.system)
          ),
        name = name
      )

    actorContext.actorOf(
      ClusterSingletonProxy
        .props(
          singletonManagerPath = singleton.path.toStringWithoutAddress,
          settings = ClusterSingletonProxySettings(actorContext.system)
        ),
      name = name + "Proxy"
    )

  }

  def clusterSingleton(
    props: Props,
    name: String,
    dispatcher: String
  )(
    implicit actorContext: ActorContext
  ): ActorRef = {
    val singleton = actorContext
      .actorOf(
        ClusterSingletonManager
          .props(
            singletonProps = props.withDispatcher(dispatcher),
            terminationMessage = PoisonPill,
            settings = ClusterSingletonManagerSettings(actorContext.system)
          )
          .withDispatcher(dispatcher),
        name = name
      )

    actorContext.actorOf(
      ClusterSingletonProxy
        .props(
          singletonManagerPath = singleton.path.toStringWithoutAddress,
          settings = ClusterSingletonProxySettings(actorContext.system)
        )
        .withDispatcher(dispatcher),
      name = name + "Proxy"
    )
  }

  object Configuration {
    val configPath = "root-actor"
    val resultsDir = "results-directory"
  }

  def props = Props(new RootActor)

  case object StartCrossValidation extends RootActorMessage
  case object StartApplication extends RootActorMessage

  sealed trait RootActorMessage

}

class RootActor extends Actor with Stash {
  import RootActor._

  implicit private val config: Config = ConfigFactory.load()
  private val rootActorConfig = config.getConfig(Configuration.configPath)
  private val resultsDir = rootActorConfig.getString(Configuration.resultsDir)
  implicit private val actorContext: ActorContext = context

  private val log = Logger(getClass)

  private val resultsAggregator = clusterSingleton(
    ResultsAggregator.props,
    name = ResultsAggregator.name
  )

  private val modelManager =
    clusterSingleton(
      ModelManager
        .props(
          config
            .getConfig(
              ModelManager.Configuration.configPath
            ),
          self,
          resultsAggregator
        ),
      ModelManager.name,
      "model-dispatcher"
    )

  private val cleanDataManager = clusterSingleton(
    CleanDataManager
      .props(
        config = config
          .getConfig(
            CleanDataManager.Configuration.configPath
          ),
        modelManager = modelManager
      ),
    name = CleanDataManager.name,
    dispatcher = "clean-dispatcher"
  )

  private val loadDataManager = clusterSingleton(
    LoadDataManager
      .props(
        config.getConfig(LoadDataManagerLogic.Configuration.configPath),
        cleanDataManager = cleanDataManager,
        resultsAggregator = resultsAggregator
      ),
    LoadDataManager.name,
    "load-dispatcher"
  )

  private val dictionaryResolver = clusterSingleton(
    DictionaryResolver.props(
      config = config.getConfig(DictionaryResolver.Configuration.configPath),
      loadDataManager = loadDataManager,
      rootActor = self
    ),
    name = DictionaryResolver.name
  )

  override def receive: Receive = startApplication
  private var bag: Option[Bag[String]] = None
  private var bayesSize: Option[Int] = None

  private def startApplication: Receive = {
    case RootActor.StartApplication =>
      log.info("Starting application")
      dictionaryResolver ! DictionaryResolver.ResolveDictionary

    case msg: DictionaryResolver.DictionaryResolved =>
      log.info("Dictionary resolved")
      context.become(crossValidation)

      bag = Some(msg.bag)
      bayesSize = Some(msg.bayesSize)

      cleanDataManager ! CleanDataManager.ShareBag(msg.bag)
      modelManager ! ModelMessages.FeatureSizeForBayes(msg.bayesSize)
      loadDataManager ! LoadDataManager.DictionaryExists
      self ! RootActor.StartCrossValidation

      unstashAll()

    case _ =>
      stash()

  }

  private def crossValidation: Receive = {
    case StartCrossValidation =>
      log.info("Starting cross validation")
      loadDataManager ! LoadDataManager.StartCrossValidation

    case ModelMessages.LastPredictionMade =>
      resultsAggregator ! ResultsAggregator.WriteResults
      modelManager ! ModelMessages.WriteModels
      loadDataManager ! LoadDataManager.StartCrossValidation

    case ModelMessages.Trained =>
      loadDataManager ! LoadDataManager.ContinueCrossValidation
    case _ =>
      ()
  }

}
