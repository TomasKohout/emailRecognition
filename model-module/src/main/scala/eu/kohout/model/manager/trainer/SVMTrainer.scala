package eu.kohout.model.manager
import akka.actor.ActorRef
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.ModelManager.Configuration
import eu.kohout.model.manager.traits.Trainer
import smile.classification.SVM
import smile.math.kernel.{GaussianKernel, LinearKernel, MercerKernel}

class SVMTrainer(
  config: Config,
  val predictors: ActorRef,
  val writeModelTo: String)
    extends Trainer[SVM[Array[Double]]] {
  override val log: Logger = Logger(self.path.toStringWithoutAddress)

  override val name: String = "SVM"

  def chooseKernel: String => MercerKernel[Array[Double]] = {
    case "GAUSSIAN" => new GaussianKernel(config.getDouble(Configuration.SVM.sigma))
    case "LINEAR"   => new LinearKernel
    case other =>
      throw new IllegalStateException(s"$other kernel is not supported for now!")
  }

  override def trainModel: (
    Array[Array[Double]],
    Array[Int]
  ) => SVM[Array[Double]] = { (x, y) =>
    val model =
      new SVM[Array[Double]](chooseKernel(config.getString(Configuration.SVM.kernel)), 1.0, 2)
    model.learn(x, y)
    model.finish()
    model
  }

}
