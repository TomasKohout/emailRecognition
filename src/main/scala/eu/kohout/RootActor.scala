package eu.kohout

import akka.Done
import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, PoisonPill, Props, Stash}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import eu.kohout.aggregator.ResultsAggregator
import eu.kohout.cleandata.CleanDataManager
import eu.kohout.dictionary.DictionaryResolver
import eu.kohout.loaddata.{LoadDataManager, LoadDataManagerLogic}
import eu.kohout.model.manager.{ModelManager, ModelMessages}
import eu.kohout.rest.HttpMessages
import eu.kohout.rest.{HttpServer, HttpServerHandler}
import smile.feature.Bag

import scala.concurrent.duration._

object RootActor {
  val name = "RootActor"

  def startSharding(
    system: ActorSystem,
    props: Props,
    idExtractor: ExtractEntityId,
    shardResolver: ExtractShardId,
    name: String
  ): ActorRef =
    ClusterSharding(system).start(
      typeName = name,
      entityProps = props,
      settings = ClusterShardingSettings(system),
      extractEntityId = idExtractor,
      extractShardId = shardResolver
    )

  object Configuration {
    val configPath = "root-actor"
    val resultsDir = "results-directory"
  }

  def props = Props(new RootActor)

  case object StartCrossValidation extends RootActorMessage
  case object StartApplication extends RootActorMessage
  case object TrainModel extends RootActorMessage

  sealed trait RootActorMessage

}

class RootActor extends Actor with Stash {
  import RootActor._

  implicit private val config: Config = ConfigFactory.load()
  private val rootActorConfig = config.getConfig(Configuration.configPath)
  private val resultsDir = rootActorConfig.getString(Configuration.resultsDir)


  private val log = Logger(getClass)

  private val resultsAggregator = startSharding(
    system = context.system,
    props = ResultsAggregator.props,
    idExtractor = ResultsAggregator.idExtractor,
    shardResolver = ResultsAggregator.shardResolver,
    name = ResultsAggregator.name
  )

  private val modelManager =
    startSharding(
      system = context.system,
      props = ModelManager
        .props(
          config
            .getConfig(
              ModelManager.Configuration.configPath
            ),
          self,
          resultsAggregator
        ).withDispatcher("model-dispatcher"),
      shardResolver = ModelManager.shardResolver,
      idExtractor = ModelManager.idExtractor,
      name = ModelManager.name
    )

  private val cleanDataManager = startSharding(
    system = context.system,
    props = CleanDataManager
      .props(
        config = config
          .getConfig(
            CleanDataManager.Configuration.configPath
          ),
        modelManager = modelManager
      ).withDispatcher("clean-dispatcher"),
    shardResolver = CleanDataManager.shardResolver,
    idExtractor = CleanDataManager.idExtractor,
    name = CleanDataManager.name
  )

  val httpServer = new HttpServer(config, new HttpServerHandler(cleanDataManager, self)(5 seconds))(context.system)

  private val loadDataManager = startSharding(
    system = context.system,
    LoadDataManager
      .props(
        config.getConfig(LoadDataManagerLogic.Configuration.configPath),
        cleanDataManager = cleanDataManager,
        resultsAggregator = resultsAggregator
      ).withDispatcher("load-dispatcher"),
    shardResolver = LoadDataManager.shardResolver,
    idExtractor = LoadDataManager.idExtractor,
    name = LoadDataManager.name


  )

  private val dictionaryResolver = startSharding(
    system = context.system,
    DictionaryResolver.props(
      config = config.getConfig(DictionaryResolver.Configuration.configPath),
      loadDataManager = loadDataManager,
      rootActor = self
    ),
    shardResolver = DictionaryResolver.shardResolver,
    idExtractor = DictionaryResolver.idExtractor,
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
      context.become(started)

      bag = Some(msg.bag)
      bayesSize = Some(msg.bayesSize)

      cleanDataManager ! CleanDataManager.ShareBag(msg.bag)
      modelManager ! ModelMessages.FeatureSizeForBayes(msg.bayesSize)
      loadDataManager ! LoadDataManager.DictionaryExists
//      self ! RootActor.StartCrossValidation

      unstashAll()

    case _ =>
      stash()

  }

  private def started: Receive = {
    case HttpMessages.RootActor.KillActors =>
      resultsAggregator ! PoisonPill
      dictionaryResolver ! PoisonPill
      loadDataManager ! PoisonPill
      cleanDataManager ! PoisonPill
      modelManager ! PoisonPill

    case HttpMessages.RootActor.StartActors =>
      resultsAggregator ! Done
      dictionaryResolver ! Done
      loadDataManager ! Done
      cleanDataManager ! Done
      modelManager ! Done

    case HttpMessages.RootActor.StartCrossValidation =>
      log.info("Starting cross validation")
      loadDataManager ! LoadDataManager.StartCrossValidation

    case ModelMessages.LastPredictionMade =>
      resultsAggregator ! ResultsAggregator.WriteResults
      modelManager ! ModelMessages.WriteModels
      loadDataManager ! LoadDataManager.StartCrossValidation

    case ModelMessages.Trained =>
      loadDataManager ! LoadDataManager.ContinueCrossValidation

    case HttpMessages.RootActor.TrainModel =>
      log.info("Beginning the process of training model")
      modelManager ! ModelMessages.WriteModels
      loadDataManager ! LoadDataManager.LoadTrainData
    case _ =>
      ()
  }

}
