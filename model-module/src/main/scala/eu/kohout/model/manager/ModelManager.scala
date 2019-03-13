package eu.kohout.model.manager

import akka.actor.{Actor, ActorRef, Cancellable, PoisonPill, Props}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.pattern.ask
import akka.routing._
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import ModelMessages._
import akka.Done
import akka.cluster.sharding.ShardRegion
import eu.kohout.aggregator.{Model, ModelType, ResultsAggregator}
import eu.kohout.aggregator.ResultsAggregator.AfterPrediction
import eu.kohout.model.manager.ModelManager.Configuration
import eu.kohout.parser.EmailType.{Ham, Spam}
import smile.classification.{NaiveBayes, SVM}
import smile.math.kernel.{GaussianKernel, LinearKernel, MercerKernel}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object ModelManager {
  val name = "Model"

  object Configuration {
    val configPath = "model"

    trait GenericConfig {
      protected def configPath: String
      def shareAfter: String = "share-model-after"
      def numberOfPredictors: String = s"$configPath.number-of-predictors"
      def sigma: String = s"$configPath.sigma"
    }

    object NaiveBayes extends GenericConfig {
      val name = "NaiveBayes"
      override val configPath: String = "naive-bayes"
      val model = s"$configPath.model"
    }

    object SVM extends GenericConfig {
      val name = "svm"
      override val configPath: String = "svm"
      val kernel = s"$configPath.kernel"
    }

  }

  val idExtractor: ShardRegion.ExtractEntityId = {
    case msg => (name, msg)
  }

  val shardResolver: ShardRegion.ExtractShardId =
    _ => (math.abs(name.hashCode) % 100).toString

  private def apply(
    config: Config,
    rootActor: ActorRef,
    resultsAggregator: ActorRef
  ): ModelManager = new ModelManager(config, rootActor, resultsAggregator)

  def props(
    config: Config,
    rootActor: ActorRef,
    resultsAggregator: ActorRef
  ): Props = Props(ModelManager(config, rootActor, resultsAggregator))
}

class ModelManager(
  config: Config,
  rootActor: ActorRef,
  resultsAggregator: ActorRef)
    extends Actor {
  private val log = Logger(self.path.toStringWithoutAddress)

  //TODO move this into config
  implicit private val timeout: Timeout = 5 seconds
  implicit private val ec: ExecutionContext = context.dispatcher

  private val defaultTrainModels = Seq(ModelType.NaiveBayes, ModelType.SVM)
  private var htmlEvaluator: ActorRef = _

  private var scheduledMessage: Option[Cancellable] = None

  private val naiveRoutees: ActorRef =
    createPredictors(
      config.getInt(Configuration.NaiveBayes.numberOfPredictors),
      Configuration.NaiveBayes.name
    )

  private val svmRoutees: ActorRef =
    createPredictors(config.getInt(Configuration.SVM.numberOfPredictors), Configuration.SVM.name)

  private var naiveTrainer: ActorRef = _

  private val svmTrainer: ActorRef = startTrainer(
    props = GenericTrainer.props(
      model = _ => {
        new SVM[Array[Double]](chooseKernel(config.getString(Configuration.SVM.kernel)), 1.0, 2)
      },
      predictors = svmRoutees,
      writeModelTo = config.getString("write-model-to")
    ),
    specificConfig = config.getConfig("svm.trainer"),
    name = "SVMTrainer"
  )

  private def trainModels(message: TrainData): Future[Unit] =
    Future
      .sequence(defaultTrainModels map {
        case ModelType.SVM =>
          svmTrainer.ask(message)(timeout = 10 minutes)
        case ModelType.NaiveBayes =>
          naiveTrainer.ask(message)(timeout = 10 minutes)
      }) map (_ => rootActor ! ModelMessages.Trained) map (_ => self ! SwitchToPrediction)

  private def receivePredict(
    message: Predict,
    replyTo: ActorRef
  ): Future[Unit] = {
    val naivePredict = (naiveRoutees ? message.data)
      .map {
        case result: PredictResult =>
          result
      }

    val svmPredict = (svmRoutees ? message.data)
      .map {
        case result: PredictResult =>
          result
      }

    for {
      naiveResult <- naivePredict
      svmResult <- svmPredict
      resultModels = List(
        Model(
          naiveResult.result,
          ModelType.NaiveBayes
        ),
        Model(
          svmResult.result,
          ModelType.SVM
        )
      )
      typeOfEmail = if (naiveResult.result == 1 && svmResult.result == -1) Ham
      else if (naiveResult.result == -1 && svmResult.result == 1) Ham
      else if (naiveResult.result == 1 && svmResult.result == 1) Ham
      else Spam
      percent = svmResult.result + naiveResult.result
      result = AfterPrediction(
        id = message.data.id,
        `type` = typeOfEmail,
        percent = percent,
        models = resultModels
      )
    } yield {
      replyTo ! result
      resultsAggregator ! result
    }
  }

  private def shiftScheduledMessage(
    cancellable: Option[Cancellable],
    receiver: ActorRef,
    modelMessages: ModelMessages
  ): Option[Cancellable] =
    cancellable.fold(
      Some(context.system.scheduler.scheduleOnce(30 seconds, receiver, modelMessages))
    ) { cancellable =>
      cancellable.cancel()
      Some(context.system.scheduler.scheduleOnce(30 seconds, receiver, modelMessages))
    }

  private var trainData: Seq[CleansedEmail] = Seq.empty

  private def writeAndForgot(): Unit = {
    svmTrainer ! WriteModels
    naiveTrainer ! WriteModels
    svmTrainer ! ForgotModel
    naiveTrainer ! ForgotModel
    naiveRoutees ! Broadcast(ForgotModel)
    svmRoutees ! Broadcast(ForgotModel)
  }

  private def predictState: Receive = {
    case WriteModels =>
      writeAndForgot()
      context.become(trainState)
      log.info("Going to train state, completely forgot all.")

    case message: Predict =>
      val replyTo = sender()
      log.debug("Prediction for id {}", message.data.id)
      receivePredict(message, replyTo)

      scheduledMessage =
        shiftScheduledMessage(scheduledMessage, rootActor, ModelMessages.LastPredictionMade)

    case Done => throw new Exception ("Reseting actor")

    case other =>
      log.warn("Prediction state")
      log.warn(s"$other message")

  }

  private def shiftState: Receive = {
    case SwitchToPrediction =>
      log.info("Going to predictState")
      context.become(predictState)

    case Done => throw new Exception ("Reseting actor")

    case other =>
      log.warn("Shift state")
      log.warn(s"$other message")
  }

  private def trainState: Receive = {
    case message: Train =>
      trainData = trainData :+ message.data

      scheduledMessage = shiftScheduledMessage(scheduledMessage, self, ModelMessages.TrainModels)

    case TrainModels =>
      log.info("Going to train models")
      trainModels(TrainData(trainData))
      context.become(shiftState)
      ()

    case Done => throw new Exception ("Reseting actor")

    case other =>
      log.warn("Train state")
      log.warn(s"$other message")
  }

  override def receive: Receive = startingState

  private def startingState: Receive = {
    case msg: FeatureSizeForBayes =>
      naiveTrainer = startTrainer(
        props = GenericTrainer
          .props(
            model = _ => {
              new NaiveBayes(
                chooseModel(
                  config
                    .getString(
                      Configuration.NaiveBayes.model
                    )
                ),
                2,
                msg.size,
                config
                  .getDouble(
                    Configuration.NaiveBayes.sigma
                  )
              )
            },
            predictors = naiveRoutees,
            writeModelTo = config.getString("write-model-to")
          ),
        specificConfig = config.getConfig("naive-bayes.trainer"),
        name = "NaiveTrainer"
      )

      log.info("Going to trainState")
      context.become(trainState)

    case other =>
      log.warn("Starting state")
      log.warn(s"$other message")
  }

  def startTrainer(
    props: Props,
    specificConfig: Config,
    name: String
  ): ActorRef = {
    val singleton = context.actorOf(
      ClusterSingletonManager
        .props(
          singletonProps = props.withDispatcher("model-dispatcher"),
          terminationMessage = PoisonPill,
          settings = ClusterSingletonManagerSettings(specificConfig)
        )
        .withDispatcher("model-dispatcher")
    )

    context.actorOf(
      ClusterSingletonProxy
        .props(
          singletonManagerPath = singleton.path.toStringWithoutAddress,
          settings = ClusterSingletonProxySettings(specificConfig)
        )
        .withDispatcher("model-dispatcher")
    )
  }

  def createPredictors(
    numberOfPredictors: Int,
    modelName: String
  ): ActorRef = {
    require(numberOfPredictors > 0, "At least on predictor must be created!")

    context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(numberOfPredictors),
        ClusterRouterPoolSettings(
          totalInstances = numberOfPredictors * 10,
          maxInstancesPerNode = numberOfPredictors,
          allowLocalRoutees = true
        )
      ).props(GenericPredictor.props)
        .withDispatcher("model-dispatcher"),
      name = GenericPredictor.name(modelName)
    )
  }

  def chooseModel: String => NaiveBayes.Model = {
    case "MULTINOMIAL" => NaiveBayes.Model.MULTINOMIAL
    case "BERNOULLI"   => NaiveBayes.Model.BERNOULLI
    case "POLYAURN"    => NaiveBayes.Model.POLYAURN
    case other =>
      throw new IllegalStateException(s"$other model is currently not supported!")
  }

  def chooseKernel: String => MercerKernel[Array[Double]] = {
    case "GAUSSIAN" => new GaussianKernel(config.getDouble(Configuration.SVM.sigma))
    case "LINEAR"   => new LinearKernel
    case other =>
      throw new IllegalStateException(s"$other kernel is not supported for now!")
  }
}
