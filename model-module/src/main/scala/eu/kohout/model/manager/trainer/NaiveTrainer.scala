package eu.kohout.model.manager
import akka.actor.ActorRef
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.ModelManager.Configuration
import eu.kohout.model.manager.traits.Trainer
import smile.classification.NaiveBayes

class NaiveTrainer(
  config: Config,
  fetureSize: Int,
  val predictors: ActorRef,
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
            Configuration.NaiveBayes.model
          )
      ),
      2,
      fetureSize,
      config
        .getDouble(
          Configuration.NaiveBayes.sigma
        )
    )
    model.learn(x, y)
    model
  }

}
