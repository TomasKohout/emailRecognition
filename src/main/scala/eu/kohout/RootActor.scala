package eu.kohout

import akka.Done
import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, PoisonPill, Props, Stash}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import com.thoughtworks.xstream.XStream
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import eu.kohout.Main.actorSystem
import eu.kohout.aggregator.ResultsAggregator
import eu.kohout.cleandata.CleanDataManager
import eu.kohout.dictionary.DictionaryResolver
import eu.kohout.loaddata.{LoadDataManager, LoadDataManagerLogic}
import eu.kohout.model.manager.{ModelManager, ModelMessages}
import eu.kohout.rest.HttpMessages
import eu.kohout.rest.{HttpServer, HttpServerHandler}
import smile.feature.Bag

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

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

  private val selfProxy = actorSystem.actorOf(
    ClusterSingletonProxy
      .props(
        singletonManagerPath = context.parent.path.toStringWithoutAddress,
        settings = ClusterSingletonProxySettings(actorSystem)
      ),
    name = RootActor.name + "Proxy"
  )

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
          selfProxy,
          resultsAggregator
        ).withDispatcher("model-dispatcher"),
      name = ModelManager.name
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
        resultsAggregator = resultsAggregator,
        rootActor = selfProxy
      ),
    LoadDataManager.name,
    "load-dispatcher"
  )

  private val dictionaryResolver = clusterSingleton(
    DictionaryResolver.props(
      config = config.getConfig(DictionaryResolver.Configuration.configPath),
      loadDataManager = loadDataManager,
      rootActor = selfProxy
    ),
    name = DictionaryResolver.name
  )

  val httpServer = new HttpServer(config, new HttpServerHandler(cleanDataManager, selfProxy, resultsAggregator)(5 seconds))(context.system)

  override def receive: Receive = startApplication

  implicit val ec: ExecutionContext = context.dispatcher

  private var bag: Option[Bag[String]] = None
  private var bayesSize: Option[Int] = None
  private val xStream = new XStream

  private def startApplication: Receive = {
    case HttpMessages.RootActor.StartApplication =>
      log.info("Starting application")
      dictionaryResolver ! DictionaryResolver.ResolveDictionary

    case RootActor.StartApplication =>
      log.info("Starting application")
      dictionaryResolver ! DictionaryResolver.ResolveDictionary

    case msg: DictionaryResolver.DictionaryResolved =>
      log.info("Dictionary resolved")

      bag = Some(xStream.fromXML(msg.bag).asInstanceOf[Bag[String]])
      bayesSize = Some(msg.bayesSize)

      cleanDataManager ! CleanDataManager.ShareBag(msg.bag)
      modelManager ! ModelMessages.FeatureSizeForBayes(msg.bayesSize)
      loadDataManager ! LoadDataManager.DictionaryExists
      context.become(waitingForOrders)
      unstashAll()

    case _ =>
      stash()

  }

  private def crossValidation: Receive = {
    case HttpMessages.RootActor.StartCrossValidation =>
      log.info("Starting cross validation")

      loadDataManager ! LoadDataManager.StartCrossValidation
      modelManager ! ModelMessages.WriteModels
      modelManager ! ModelMessages.SetShiftMessage

    case ModelMessages.LastPredictionMade =>
      resultsAggregator ! ResultsAggregator.WriteResults
      modelManager ! ModelMessages.WriteModels
      modelManager ! ModelMessages.SetShiftMessage
      loadDataManager ! LoadDataManager.StartCrossValidation

    case ModelMessages.Trained =>
      loadDataManager ! LoadDataManager.ContinueCrossValidation
      modelManager ! ModelMessages.SetShiftMessage

    case LoadDataManager.CrossValidationDone =>
      log.info("Cross validation is done")
      modelManager ! ModelMessages.WriteModels

      context.become(waitingForOrders)

    case other =>
      log.warn("Unsupported message received {}", other)
  }

  private def waitingForOrders: Receive = {
    case HttpMessages.RootActor.RestartActors =>
      resultsAggregator ! Done
      dictionaryResolver ! Done
      loadDataManager ! Done
      cleanDataManager ! Done
      modelManager ! Done

      cleanDataManager ! CleanDataManager.ShareBag(bag.map(xStream.toXML).getOrElse(throw new Exception("Does not have a bag!")))
      modelManager ! ModelMessages.FeatureSizeForBayes(bayesSize.getOrElse(throw new Exception("Does not have a bayes size!")))
      loadDataManager ! LoadDataManager.DictionaryExists

    case HttpMessages.RootActor.StartCrossValidation =>
      context.become(crossValidation)
      self ! HttpMessages.RootActor.StartCrossValidation

    case HttpMessages.RootActor.TrainModel =>
      context.become(trainModels)
      self ! HttpMessages.RootActor.TrainModel

    case HttpMessages.RootActor.Terminate =>
      log.info("Terminating actor system, bye.")
      context.system.terminate()
      ()

    case other =>
      log.warn("Unsupported message received {}", other)

  }

  private def trainModels: Receive = {
    case HttpMessages.RootActor.TrainModel =>
      log.info("Beginning the process of training model")


      modelManager ! ModelMessages.WriteModels
      loadDataManager ! LoadDataManager.LoadTrainData
      modelManager ! ModelMessages.SetShiftMessage

    case ModelMessages.Trained =>
      log.info("Models trained, becoming waitingForOrders")
      context.become(waitingForOrders)

    case other =>
      log.warn("Unsupported message received {}", other)

  }
}
