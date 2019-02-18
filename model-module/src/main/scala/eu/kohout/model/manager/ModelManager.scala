package eu.kohout.model.manager
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.singleton.{
  ClusterSingletonManager,
  ClusterSingletonManagerSettings,
  ClusterSingletonProxy,
  ClusterSingletonProxySettings
}
import akka.routing.{ActorRefRoutee, ConsistentHashingPool, RoundRobinRoutingLogic, Router}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.ModelManager.Configuration
import eu.kohout.model.manager.messages.ModelMessages.{CleansedEmail, Predict, Train}
import smile.classification.{NaiveBayes, OnlineClassifier, SVM}
import smile.classification.NaiveBayes.Model
import smile.math.kernel.{GaussianKernel, LinearKernel, MercerKernel}

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
  private def apply(config: Config): ModelManager = new ModelManager(config)
  def props(config: Config): Props = Props(ModelManager(config))
}

class ModelManager(config: Config) extends Actor {
  private val log = Logger(self.path.toStringWithoutAddress)
  log.info(Configuration.NaiveBayes.numberOfPredictors)
  var htmlEvaluater: ActorRef = _

  val naiveRoutees: ActorRef =
    createPredictors(
      config.getInt(Configuration.NaiveBayes.numberOfPredictors),
      Configuration.NaiveBayes.name
    )

  val svmRoutees: ActorRef =
    createPredictors(config.getInt(Configuration.SVM.numberOfPredictors), Configuration.SVM.name)

  log.info("NAIVE TRAINER")
  log.info("SVM TRAINER")
  log.info("{}", config.getConfig("model.svm.trainer"))

  val naiveTrainer: ActorRef = startTrainer(
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
            50,
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

  val svmTrainer: ActorRef = startTrainer(
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

  override def receive: Receive = {
    case message: Train =>
      naiveTrainer ! message
    case message: Predict =>
      naiveRoutees.tell(message.data, sender = sender())
    case message: CleansedEmail =>
      log.info("HLEDAMICEK S ID {}", message.id)
      naiveTrainer ! message

    case other =>
      log.error("other message")
  }

  def startTrainer(
    props: Props,
    specificConfig: Config,
    name: String
  ): ActorRef = {

    log.info("lFJ``lsjdfjalsfjs name {}", name)
    log.info("{}", specificConfig.toString)
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
        ConsistentHashingPool(numberOfPredictors),
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
