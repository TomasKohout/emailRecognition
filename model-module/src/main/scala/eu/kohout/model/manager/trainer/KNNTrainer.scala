package eu.kohout.model.manager.trainer

import akka.actor.{ActorRef, Props}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.trainer.KNNTrainer.Configuration
import eu.kohout.model.manager.trainer.template.Trainer
import smile.classification.KNN

object KNNTrainer {
  val name: String = "KNN"

  object Configuration {
    val configPath = "knn"
    val k = "k"
  }

  def props(
    knnConfig: Config,
    predictors: ActorRef,
    countOfPredictors: Int,
    writeModelTo: String
  ): Props =
    Props(new KNNTrainer(knnConfig = knnConfig, predictors = predictors, writeModelTo = writeModelTo, countOfPredictors = countOfPredictors))
}

class KNNTrainer(
  knnConfig: Config,
  val predictors: ActorRef,
  val countOfPredictors: Int,
  val writeModelTo: String)
    extends Trainer[KNN[Array[Double]]] {
  override val log: Logger = Logger(self.path.toStringWithoutAddress)

  override val name: String = "KNN"

  private val k = knnConfig.getInt(Configuration.k)

  override def trainModel: (
    Array[Array[Double]],
    Array[Int]
  ) => KNN[Array[Double]] = KNN.learn(_, _, k)
}
