package eu.kohout.model.manager.trainer

import akka.actor.{ActorRef, Props}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.trainer.NaiveTrainer.Configuration
import eu.kohout.model.manager.trainer.template.Trainer
import smile.classification.NaiveBayes

object NaiveTrainer {
  val name: String = "NaiveBayes"

  object Configuration {
    val configPath: String = "naive-bayes"
    val model = "model"
    val sigma = "sigma"
  }

  def props(
    config: Config,
    featureSize: Int,
    predictors: ActorRef,
    countOfPredictors: Int,
    writeModelTo: String
  ): Props =
    Props(
      new NaiveTrainer(
        config = config,
        featureSize = featureSize,
        predictors = predictors,
        writeModelTo = writeModelTo,
        countOfPredictors = countOfPredictors
      )
    )
}

class NaiveTrainer(
  config: Config,
  featureSize: Int,
  val predictors: ActorRef,
  val countOfPredictors: Int,
  val writeModelTo: String)
    extends Trainer[NaiveBayes] {
  override val log: Logger = Logger(self.path.toStringWithoutAddress)

  override val name: String = "NaiveBayes"

  def chooseModel: String => NaiveBayes.Model = {
    case "MULTINOMIAL" => NaiveBayes.Model.MULTINOMIAL
    case "BERNOULLI"   => NaiveBayes.Model.BERNOULLI
    case "POLYAURN"    => NaiveBayes.Model.POLYAURN
    case other =>
      throw new IllegalStateException(s"$other model is currently not supported!")
  }

  override def trainModel: (
    Array[Array[Double]],
    Array[Int]
  ) => NaiveBayes = { (x, y) =>
    val model = new NaiveBayes(
      chooseModel(
        config
          .getString(
            Configuration.model
          )
      ),
      2,
      featureSize,
      config
        .getDouble(
          Configuration.sigma
        )
    )
    model.learn(x, y)
    model
  }

}
