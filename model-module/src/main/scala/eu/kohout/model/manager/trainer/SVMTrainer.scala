package eu.kohout.model.manager.trainer

import akka.actor.{ActorRef, Props}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.trainer.SVMTrainer.Configuration
import eu.kohout.model.manager.traits.Trainer
import smile.classification.SVM
import smile.math.kernel.{GaussianKernel, LinearKernel, MercerKernel}

object SVMTrainer {
  val name: String = "SVM"

  object Configuration {
    val configPath: String = "svm"
    val shareAfter: String = "share-model-after"
    val numberOfPredictors: String = s"$configPath.number-of-predictors"
    val kernel = "kernel"
    val sigma = "sigma"
  }

  def props(
    config: Config,
    predictors: ActorRef,
    writeModelTo: String
  ): Props =
    Props(
      new SVMTrainer(
        config = config,
        predictors = predictors,
        writeModelTo = writeModelTo
      )
    )
}

class SVMTrainer(
  config: Config,
  val predictors: ActorRef,
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
      new SVM[Array[Double]](chooseKernel(config.getString(Configuration.kernel)), 1.0, 2)
    model.learn(x, y)
    model.finish()
    model
  }

}
