package eu.kohout.model.manager.trainer

import akka.actor.{ActorRef, Props}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.trainer.AdaBoostTrainer.Configuration
import eu.kohout.model.manager.trainer.template.Trainer
import smile.classification.AdaBoost

object AdaBoostTrainer {
  val name: String = "AdaBoost"

  object Configuration  {
    val configPath = "adaboost"
    val ntrees = "number-of-trees"
    val maxNodes = "max-nodes"
  }

  def props(
    adaBoostConfig: Config,
    predictors: ActorRef,
    countOfPredictors: Int,
    writeModelTo: String
  ): Props =
    Props(
      new AdaBoostTrainer(adaBoostConfig = adaBoostConfig, predictors = predictors, writeModelTo = writeModelTo, countOfPredictors = countOfPredictors)
    )
}

class AdaBoostTrainer(
  adaBoostConfig: Config,
  val predictors: ActorRef,
  val countOfPredictors: Int,
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
