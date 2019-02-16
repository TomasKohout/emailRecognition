package eu.kohout.model.manager
import akka.actor.{Actor, ActorRef, Props}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import com.typesafe.config.Config
import eu.kohout.model.manager.ModelManager.Configuration
import eu.kohout.model.manager.messages.ModelMessages.{Predict, Train}
import smile.classification.{NaiveBayes, OnlineClassifier, SVM}
import smile.classification.NaiveBayes.Model
import smile.math.kernel.{GaussianKernel, LinearKernel, MercerKernel}

object ModelManager {
  val name = "ModelManager"

  object Configuration {
    val configPath = "model"

    trait GenericConfig {
      val configPath: String
      val shareAfter: String = s"$configPath.shareAfter"
      val numberOfPredictors: String = s"$configPath.number-of-predictors"
      val sigma: String = s"$configPath.sigma"
    }

    object NaiveBayes extends GenericConfig {
      val name = "NaiveBayes"
      override val configPath: String = s"$configPath.naive-bayes"
      val model = s"$configPath.model"
    }

    object SVM extends GenericConfig {
      val name = "SVM"
      override val configPath: String = s"$configPath.SVM"
      val kernel = s"$configPath.kernel"
    }

  }
  private def apply(config: Config): ModelManager = new ModelManager(config)
  def props(config: Config): Props = Props(ModelManager(config))
}

class ModelManager(config: Config) extends Actor {

  var htmlEvaluater: ActorRef = _

  val naiveRoutees: Router =
    createPredictors(
      config.getInt(Configuration.NaiveBayes.numberOfPredictors),
      Configuration.NaiveBayes.name
    )

  val svmRoutees: Router =
    createPredictors(config.getInt(Configuration.SVM.numberOfPredictors), Configuration.SVM.name)

  val naiveTrainer: ActorRef = startTrainer(
    predictors = naiveRoutees,
    modelName = Configuration.NaiveBayes.name,
    createModel = _ => {
      new NaiveBayes(
        chooseModel(
          config
            .getString(
              Configuration.NaiveBayes.model
            )
        ),
        2,
        1,
        config
          .getDouble(
            Configuration.NaiveBayes.sigma
          )
      )
    },
    shareAfter = 100
  )

  val svmTrainer: ActorRef = startTrainer(
    predictors = svmRoutees,
    modelName = Configuration.SVM.name,
    createModel = _ => {
      new SVM[Array[Double]](chooseKernel(config.getString(Configuration.SVM.kernel)), 1.0, 2)
    },
    shareAfter = config.getInt(Configuration.SVM.shareAfter)
  )

  override def receive: Receive = {
    case message: Train =>
      naiveTrainer ! message.data
    case message: Predict =>
      naiveRoutees.route(message.data, sender = sender())
  }

  def startTrainer(
    predictors: Router,
    modelName: String,
    createModel: Unit => OnlineClassifier[Array[Double]],
    shareAfter: Int
  ): ActorRef = {
    require(shareAfter > 0, s"${Configuration.NaiveBayes.shareAfter} must be greater then 0!")
    context
      .actorOf(
        GenericTrainer
          .props(
            model = createModel(()),
            predictors = predictors,
            shareAfter = shareAfter
          ),
        GenericTrainer.name(modelName)
      )
  }

  def createPredictors(
    numberOfPredictors: Int,
    modelName: String
  ): Router = {
    require(numberOfPredictors > 0, "At least on predictor must be created!")

    var order = 0
    val routees = Vector.fill(numberOfPredictors) {

      val worker = context.actorOf(
        GenericPredictor.props,
        GenericPredictor.name(modelName) + order
      )
      order += 1
      context watch worker
      ActorRefRoutee(worker)
    }

    Router(RoundRobinRoutingLogic(), routees)
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
