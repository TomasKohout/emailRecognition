package eu.kohout.model.manager.trainer

import akka.actor.{ActorRef, Props}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.trainer.SVMTrainer.Configuration
import eu.kohout.model.manager.trainer.template.Trainer
import smile.classification.SVM
import smile.math.kernel.{GaussianKernel, LinearKernel, MercerKernel}

object SVMTrainer {
  val name: String = "SVM"

  object Configuration {
    val positiveMarginSmoothing: String = "positive-margin-smoothing"
    val negativeMarginSmoothing: String = "negative-margin-smoothing"

    val configPath: String = "svm"
    val kernel = "kernel"
    val sigma = "sigma"
  }

  def props(
    config: Config,
    predictors: ActorRef,
    countOfPredictors: Int,
    writeModelTo: String
  ): Props =
    Props(
      new SVMTrainer(
        config = config,
        predictors = predictors,
        writeModelTo = writeModelTo,
        countOfPredictors = countOfPredictors
      )
    )
}

class SVMTrainer(
  config: Config,
  val predictors: ActorRef,
  val countOfPredictors: Int,
  val writeModelTo: String)
    extends Trainer[SVM[Array[Double]]] {
  override val log: Logger = Logger(self.path.toStringWithoutAddress)

  override val name: String = "SVM"

  def chooseKernel: String => MercerKernel[Array[Double]] = {
    case "GAUSSIAN" => new GaussianKernel(config.getDouble(Configuration.sigma))
    case "LINEAR"   => new LinearKernel
    case other =>
      throw new IllegalStateException(s"$other kernel is not supported for now!")
  }

  override def trainModel: (
    Array[Array[Double]],
    Array[Int]
  ) => SVM[Array[Double]] = { (x, y) =>
    val model =
      new SVM[Array[Double]](
        chooseKernel(config.getString(Configuration.kernel)),
        config.getDouble(Configuration.positiveMarginSmoothing),
        config.getDouble(Configuration.negativeMarginSmoothing)
      )
    model.learn(x, y)
    model.finish()
    model
  }

}
