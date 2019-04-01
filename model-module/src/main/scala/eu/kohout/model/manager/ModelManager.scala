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
import eu.kohout.aggregator.{Model, ModelType}
import eu.kohout.aggregator.ResultsAggregator.AfterPrediction
import eu.kohout.model.manager.predictor.GenericPredictor
import eu.kohout.model.manager.trainer.{AdaBoostTrainer, NaiveTrainer, SVMTrainer}
import eu.kohout.parser.EmailType.{Ham, Spam}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object ModelManager {
  val name = "Model"

  object Configuration {
    val configPath = "model"
  }

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

  private val defaultTrainModels = Seq(ModelType.NaiveBayes, ModelType.AdaBoost)
  private var htmlEvaluator: ActorRef = _

  private var scheduledMessage: Option[Cancellable] = None

  private val naiveRoutees: ActorRef =
    createPredictors(
      config.getInt(NaiveTrainer.Configuration.numberOfPredictors),
      NaiveTrainer.name
    )

  private val svmRoutees: ActorRef =
    createPredictors(config.getInt(SVMTrainer.Configuration.numberOfPredictors), SVMTrainer.name)

  private var naiveTrainer: ActorRef = _

  private val svmTrainer: ActorRef = startTrainer(
    props = SVMTrainer.props(
      config = config.getConfig(SVMTrainer.Configuration.configPath),
      predictors = svmRoutees,
      writeModelTo = config.getString("write-model-to")
    ),
    specificConfig = config.getConfig("svm.trainer"),
    name = SVMTrainer.name + "Trainer"
  )

  private val adaBoostPredictors = createPredictors(
    config.getInt(AdaBoostTrainer.Configuration.numberOfPredictors),
    AdaBoostTrainer.name
  )

  private val adaBoostTrainer: ActorRef = startTrainer(
    props = AdaBoostTrainer.props(
      config.getConfig(AdaBoostTrainer.Configuration.configPath),
      adaBoostPredictors,
      config.getString("write-model-to")
    ),
    specificConfig = config.getConfig(AdaBoostTrainer.Configuration.configPath + ".trainer"),
    AdaBoostTrainer.name + "Trainer"
  )

  private def trainModels(message: TrainData): Future[Unit] =
    Future
      .sequence(defaultTrainModels map {
        case ModelType.SVM =>
          svmTrainer.ask(message)(timeout = 10 minutes)
        case ModelType.NaiveBayes =>
          naiveTrainer.ask(message)(timeout = 10 minutes)
        case ModelType.AdaBoost =>
          adaBoostTrainer.ask(message)(timeout = 10 minutes)
      }) map (_ => self ! SwitchToPrediction)

  private def receivePredict(
    message: Predict,
    replyTo: ActorRef
  ): Future[Unit] = {
    val naivePredict = (naiveRoutees ? message.data)
      .map {
        case result: PredictResult =>
          result
      }

//    val svmPredict = (svmRoutees ? message.data)
//      .map {
//        case result: PredictResult =>
//          result
//      }

    val adaPredict = (adaBoostPredictors ? message.data) map {
      case result: PredictResult => result
    }

    for {
      naiveResult <- naivePredict
//      svmResult <- svmPredict
      adaResult <- adaPredict
      resultModels = List(
        Model(
          naiveResult.result,
          ModelType.NaiveBayes
        ),
//        Model(
//          svmResult.result,
//          ModelType.SVM
//        ),
        Model(
          adaResult.result,
          ModelType.AdaBoost
        )
      )
      prediction = List(naiveResult.result, adaResult.result) //, svmResult.result)
        .groupBy(identity)
        .filter(data => data._1 == 1 || data._1 == 0)
        .reduceLeft(
          (x, y) =>
            if (x._1 == 1) {
              if (x._2.size >= y._2.size) x
              else y
            } else {
              if (y._2.size >= x._2.size) x
              else y
            }
        )
        ._1
      typeOfEmail = if (prediction == 1) Ham else Spam

      percent = prediction

      result = AfterPrediction(
        id = message.data.id,
        realType = message.data.`type`,
        predictedType = typeOfEmail,
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
    cancellable.flatMap { cancellable =>
      cancellable.cancel()
      Some(context.system.scheduler.scheduleOnce(30 seconds, receiver, modelMessages))
    }

  private var trainData: Seq[CleansedEmail] = Seq.empty

  private def writeAndForgot(): Unit = {
//    svmTrainer ! WriteModels
    naiveTrainer ! WriteModels
    adaBoostTrainer ! WriteModels
//    svmTrainer ! ForgotModel
    naiveTrainer ! ForgotModel
    adaBoostTrainer ! ForgotModel
    naiveRoutees ! Broadcast(ForgotModel)
//    svmRoutees ! Broadcast(ForgotModel)
    adaBoostPredictors ! Broadcast(ForgotModel)
  }

  private def predictState: Receive = {
    case SetShiftMessage =>
      scheduledMessage = Some(
        context.system.scheduler
          .scheduleOnce(30 seconds, rootActor, ModelMessages.LastPredictionMade)
      )

    case WriteModels =>
      writeAndForgot()
      context.become(trainState)
      scheduledMessage = None
      log.info("Going to train state, completely forgot everything.")

    case message: Predict =>
      val replyTo = sender()
      log.debug("Prediction for id {}", message.data.id)
      receivePredict(message, replyTo)

      scheduledMessage =
        shiftScheduledMessage(scheduledMessage, rootActor, ModelMessages.LastPredictionMade)

    case Done => throw new Exception("Reseting actor")

    case other =>
      log.warn("Prediction state")
      log.warn(s"$other message")

  }

  private def shiftState: Receive = {
    case SwitchToPrediction =>
      log.info("Going to predictState")
      context.become(predictState)
      rootActor ! Trained

    case Done => throw new Exception("Reseting actor")

    case other =>
      log.warn(s"Shift state $other")

  }

  private def trainState: Receive = {
    case SetShiftMessage =>
      scheduledMessage = Some(
        context.system.scheduler.scheduleOnce(1 minute, self, ModelMessages.TrainModels)
      )

    case message: Train =>
      trainData = trainData :+ message.data

      scheduledMessage = shiftScheduledMessage(scheduledMessage, self, ModelMessages.TrainModels)

    case TrainModels =>
      log.info("Going to train models")
      trainModels(TrainData(trainData))
      scheduledMessage = None
      context.become(shiftState)
      ()

    case Done => throw new Exception("Reseting actor")

    case other =>
      log.warn(s"Train state $other")
  }

  override def receive: Receive = startingState

  private def startingState: Receive = {
    case msg: FeatureSizeForBayes =>
      naiveTrainer = startTrainer(
        props = NaiveTrainer
          .props(
            config = config.getConfig(NaiveTrainer.Configuration.configPath),
            featureSize = msg.size,
            predictors = naiveRoutees,
            writeModelTo = config.getString("write-model-to")
          ),
        specificConfig = config.getConfig("naive-bayes.trainer"),
        name = "NaiveTrainer"
      )

      log.info("Going to trainState")
      context.become(trainState)

    case other =>
      log.warn(s"Starting state $other")

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

}
