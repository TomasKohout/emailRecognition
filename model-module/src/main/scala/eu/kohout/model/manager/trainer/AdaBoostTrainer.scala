package eu.kohout.model.manager

import akka.actor.{ActorRef, Props}
import com.typesafe.scalalogging.Logger
import com.typesafe.config.Config
import eu.kohout.model.manager.AdaBoostTrainer.Configuration
import eu.kohout.model.manager.traits.Trainer
import smile.classification.AdaBoost

object AdaBoostTrainer {
  val name: String => String = _ + "Trainer"

  object Configuration {
    val configPah = "knnConfig"
    val ntrees = "number-of-trees"
    val maxNodes = "max-nodes"
  }

  def props(
    adaBoostConfig: Config,
    predictors: ActorRef,
    writeModelTo: String
  ): Props =
    Props(
      new AdaBoostTrainer(adaBoostConfig = adaBoostConfig, predictors = predictors, writeModelTo)
    )
}

class AdaBoostTrainer(
  adaBoostConfig: Config,
  val predictors: ActorRef,
  val writeModelTo: String)
    extends Trainer[AdaBoost] {

  override val log: Logger = Logger(self.path.toStringWithoutAddress)

  override val name: String = "AdaBoost"

  val ntrees: Int = adaBoostConfig.getInt(Configuration.ntrees)
  val maxNodes: Int = adaBoostConfig.getInt(Configuration.maxNodes)

  override def trainModel: (
    Array[Array[Double]],
    Array[Int]
  ) => AdaBoost = new AdaBoost(_, _, ntrees, maxNodes)

}
