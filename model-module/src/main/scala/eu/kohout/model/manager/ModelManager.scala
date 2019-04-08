package eu.kohout.model.manager

import java.util

import akka.actor.{Actor, ActorRef, Cancellable, PoisonPill, Props}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.singleton.{
  ClusterSingletonManager,
  ClusterSingletonManagerSettings,
  ClusterSingletonProxy,
  ClusterSingletonProxySettings
}
import akka.pattern.ask
import akka.routing._
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigValueType}
import com.typesafe.scalalogging.Logger
import ModelMessages._
import akka.Done
import eu.kohout.aggregator.{Model, ModelType}
import eu.kohout.aggregator.ResultsAggregator.AfterPrediction
import eu.kohout.model.manager.ModelManager.{Actors, Configuration}
import eu.kohout.model.manager.predictor.GenericPredictor
import eu.kohout.model.manager.trainer.KNNTrainer.Configuration.configPath
import eu.kohout.model.manager.trainer.{AdaBoostTrainer, KNNTrainer, NaiveTrainer, SVMTrainer}
import eu.kohout.parser.EmailType.{Ham, Spam}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object ModelManager {
  val name = "Model"

  object Configuration {
    val configPath = "model"
    val models = "models"
    val numberOfPredictors = "number-of-predictors"
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

  case class Actors(
    trainer: ActorRef,
    predictor: ActorRef,
    weight: Int,
    modelType: ModelType)
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

  private var actors: Seq[Actors] = Seq.empty

  private val models =
    config
      .getList(ModelManager.Configuration.models)
      .asScala
      .map(
        data =>
          data.valueType() match {
            case ConfigValueType.LIST =>
              data.unwrapped().asInstanceOf[util.ArrayList[String]].asScala
            case other =>
              require(
                other == ConfigValueType.LIST,
                s"Model config is not in right format. It should be List of Lists instead of $other"
              )
              Seq.empty
          }
      )
      .foldLeft(Map.empty[String, Int]) { (map, seq) =>
        val weight = seq.last.asInstanceOf[Int]
        require(
          weight >= 0 && weight <= 100,
          "Weight of every model must be greated then 0 and lesser then 100"
        )
        map + (seq.head -> weight)
      }
      .map { case (model, weight) => ModelType.apply(model) -> weight }

  private def genericTrainerCreator: ActorRef => TrainData => Future[Unit] = {
    trainer => trainData: TrainData =>
      (trainer ? trainData)(10 minutes).map(_ => ())
  }

  private def genericPredictorCreator
    : (ActorRef, ModelType, Int) => Predict => Future[(PredictResult, ModelType, Int)] = {
    (actor, modelType, modelWeight) => (predict: Predict) =>
      (actor ? predict.data)(10 seconds).map {
        case result: PredictResult => (result, modelType, modelWeight)
      }
  }

  private var scheduledMessage: Option[Cancellable] = None

  private var train: Seq[TrainData => Future[Unit]] = Seq.empty
  private var predict: Seq[Predict => Future[(PredictResult, ModelType, Int)]] = Seq.empty

  private def trainModels(message: TrainData): Future[Unit] =
    Future.sequence(train.map(_(message))) map (_ => self ! SwitchToPrediction)

  private def receivePredict(
    message: Predict,
    replyTo: ActorRef
  ): Future[Unit] =
    for {
      results <- Future.sequence(predict.map(_(message)))
      resultModels = results
        .map { case (predictResult, modelType, _) => Model(predictResult.result, modelType) }

      prediction = results
        .groupBy(_._1.result)
        .filter(data => data._1 == 1 || data._1 == 0)
        .map {
          case (result, seq) =>
            result -> seq.map(_._3).sum
        }
        .reduceLeft[(Int,Int)]{
          case ((result0, weight0), (result1, weight1)) =>
            if (weight0 > weight1) result0 -> weight0
            else result1 -> weight1
        }
        ._1

      typeOfEmail = if (prediction == 1) Ham else Spam

      result = AfterPrediction(
        id = message.data.id,
        realType = message.data.`type`,
        predictedType = typeOfEmail,
        result = prediction,
        models = resultModels
      )
    } yield {
      replyTo ! result
      resultsAggregator ! result
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

  private def writeModels(): Unit =
    actors.foreach(_.trainer ! WriteModels)

  private def predictState: Receive = {
    case SetShiftMessage =>
      scheduledMessage = Some(
        context.system.scheduler
          .scheduleOnce(30 seconds, rootActor, ModelMessages.LastPredictionMade)
      )

    case WriteModels =>
      writeModels()
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

  private def createActors: (ModelType, Int, Int) => Actors = { (modelType, featureSize, weight) =>
    val (trainer, predictor) = modelType match {
      case ModelType.SVM =>
        val countOfPredictors = config.getInt(Configuration.numberOfPredictors)
        val predictors = createPredictors(
          countOfPredictors,
          SVMTrainer.name
        )

        startTrainer(
          props = SVMTrainer.props(
            config = config.getConfig(SVMTrainer.Configuration.configPath),
            predictors = predictors,
            writeModelTo = config.getString("write-model-to"),
            countOfPredictors = countOfPredictors
          ),
          specificConfig = config.getConfig("trainer"),
          name = SVMTrainer.name + "Trainer"
        ) -> predictors

      case ModelType.NaiveBayes =>
        val countOfPredictors = config.getInt(Configuration.numberOfPredictors)
        val predictors = createPredictors(
          countOfPredictors,
          NaiveTrainer.name
        )
        startTrainer(
          props = NaiveTrainer
            .props(
              config = config.getConfig(NaiveTrainer.Configuration.configPath),
              featureSize = featureSize,
              predictors = predictors,
              countOfPredictors = countOfPredictors,
              writeModelTo = config.getString("write-model-to")
            ),
          specificConfig = config.getConfig("trainer"),
          name = "NaiveTrainer"
        ) -> predictors
      case ModelType.AdaBoost =>
        val countOfPredictors = config.getInt(Configuration.numberOfPredictors)
        val predictors = createPredictors(
          countOfPredictors,
          AdaBoostTrainer.name
        )
        startTrainer(
          props = AdaBoostTrainer.props(
            adaBoostConfig = config.getConfig(AdaBoostTrainer.Configuration.configPath),
            predictors = predictors,
            countOfPredictors = countOfPredictors,
            writeModelTo = config.getString("write-model-to")
          ),
          specificConfig = config.getConfig("trainer"),
          AdaBoostTrainer.name + "Trainer"
        ) -> predictors

      case ModelType.KNN =>
        val countOfPredictors = config.getInt(Configuration.numberOfPredictors)
        val predictors = createPredictors(
          countOfPredictors,
          KNNTrainer.name
        )

        startTrainer(
          props = KNNTrainer.props(
            knnConfig = config.getConfig(KNNTrainer.Configuration.configPath),
            predictors = predictors,
            countOfPredictors = countOfPredictors,
            writeModelTo = config.getString("write-model-to")
          ),
          specificConfig = config.getConfig("trainer"),
          KNNTrainer.name + "Trainer"
        ) -> predictors
    }

    Actors(
      predictor = predictor,
      trainer = trainer,
      weight = weight,
      modelType = modelType
    )
  }

  private def startingState: Receive = {
    case msg: FeatureSizeForBayes =>
      actors = models.map { case (model, weight) => createActors(model, msg.size, weight) }.toSeq
      predict = actors.map(
        actors => genericPredictorCreator(actors.predictor, actors.modelType, actors.weight)
      )
      train = actors.map(actors => genericTrainerCreator(actors.trainer))

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
          settings = ClusterSingletonManagerSettings(context.system)
        )
        .withDispatcher("model-dispatcher")
    )

    context.actorOf(
      ClusterSingletonProxy
        .props(
          singletonManagerPath = singleton.path.toStringWithoutAddress,
          settings = ClusterSingletonProxySettings(context.system)
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
