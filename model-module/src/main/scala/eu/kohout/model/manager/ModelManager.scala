package eu.kohout.model.manager
import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.routing._
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.ModelManager.Configuration
import eu.kohout.model.manager.messages.ModelMessages._
import smile.classification.{NaiveBayes, SVM}
import smile.classification.NaiveBayes.Model
import smile.math.kernel.{GaussianKernel, LinearKernel, MercerKernel}
import akka.pattern.ask
import akka.util.Timeout
import eu.kohout.types.HttpMessages.EmailRecognitionResponse
import eu.kohout.types.Labels.{Ham, Spam}
import eu.kohout.types.{HttpMessages, ModelTypes}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object ModelManager {
  val name = "Model"

  def asClusterSingleton(
    props: Props,
    appCfg: Config,
    system: ActorSystem
  ): ActorRef = {

    val singleton = system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = props,
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system)
      ),
      name = name + "Manager"
    )

    system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = singleton.path.toStringWithoutAddress,
        settings = ClusterSingletonProxySettings(system)
      ),
      name = name + "Proxy"
    )

  }

  object Configuration {
    val config = "model"

    trait GenericConfig {
      val configPath: String
      def shareAfter: String = s"$configPath.share-model-after"
      def numberOfPredictors: String = s"$configPath.number-of-predictors"
      def sigma: String = s"$configPath.sigma"
    }

    object NaiveBayes extends GenericConfig {
      val name = "NaiveBayes"
      override val configPath: String = s"$config.naive-bayes"
      val model = s"$configPath.model"
    }

    object SVM extends GenericConfig {
      val name = "svm"
      override val configPath: String = s"$config.svm"
      val kernel = s"$configPath.kernel"
    }

  }
  private def apply(
    config: Config,
    resultsAggregator: ActorRef
  ): ModelManager = new ModelManager(config, resultsAggregator)

  def props(
    config: Config,
    resultsAggregator: ActorRef
  ): Props = Props(ModelManager(config, resultsAggregator))
}

class ModelManager(
  config: Config,
  resultsAggregator: ActorRef)
    extends Actor {
  private val log = Logger(self.path.toStringWithoutAddress)

  //TODO move this into config
  implicit private val timeout: Timeout = 5 seconds
  implicit private val ec: ExecutionContext = context.dispatcher

  private val defaultTrainModels = Seq(ModelTypes.SVM, ModelTypes.NaiveBayes)
  private var htmlEvaluator: ActorRef = _

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
      shareAfter = config.getInt(Configuration.SVM.shareAfter)
    ),
    specificConfig = config.getConfig("model.svm.trainer"),
    name = "SVMTrainer"
  )

  private def receiveTrain(message: TrainSeq): Unit = {
    if (message.models.isEmpty) defaultTrainModels
    else message.models
  }.foreach {
    case ModelTypes.SVM =>
      svmTrainer ! message
    case ModelTypes.NaiveBayes =>
      naiveTrainer ! message
  }

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
        HttpMessages.Model(
          naiveResult.result,
          ModelTypes.NaiveBayes
        ),
        HttpMessages.Model(
          svmResult.result,
          ModelTypes.SVM
        )
      )
      label = if (naiveResult.result == 1 || svmResult.result == 1) Ham else Spam
      percent = svmResult.result + naiveResult.result
      result = EmailRecognitionResponse(id = message.data.id, label = label, percent = percent, models = resultModels)
    } yield {
      resultsAggregator ! result
      replyTo ! result
    }
  }

  override def receive: Receive = {
    case message: TrainSeq =>
      receiveTrain(message)

    case message: Predict =>
      val replyTo = sender()
      log.debug("Prediction for id {}", message.data.id)
      receivePredict(message, replyTo)

      ()

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
            shareAfter = config.getInt(Configuration.NaiveBayes.shareAfter)
          ),
        specificConfig = config.getConfig("model.naive-bayes.trainer"),
        name = "NaiveTrainer"
      )

    case other =>
      log.error("other message")
  }

  def startTrainer(
    props: Props,
    specificConfig: Config,
    name: String
  ): ActorRef = {
    val singleton = context.actorOf(
      ClusterSingletonManager.props(
        singletonProps = props,
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(specificConfig)
      )
    )

    context.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = singleton.path.toStringWithoutAddress,
        settings = ClusterSingletonProxySettings(specificConfig)
      )
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
      ).props(GenericPredictor.props),
      name = GenericPredictor.name(modelName)
    )
  }

  def chooseModel: String => Model = {
    case "MULTINOMIAL" => Model.MULTINOMIAL
    case "BERNOULLI"   => Model.BERNOULLI
    case "POLYAURN"    => Model.POLYAURN
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
