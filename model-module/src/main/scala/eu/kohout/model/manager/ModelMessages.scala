package eu.kohout.model.manager
import akka.actor.ActorRef
import eu.kohout.aggregator.ModelType
import eu.kohout.parser.EmailType
import smile.math.SparseArray

object ModelMessages {
  case object Share extends ModelMessages

  type SerializedModel = String

  case class UpdateModel(model: SerializedModel) extends ModelMessages
  case class PredictResult(
    id: String,
    result: Int,
    `type`: EmailType)
      extends ModelMessages

  object CleansedEmail {
    implicit class ToSparseArray(array: Array[Double]) {
      implicit def toSparseArray: SparseArray =
        array
          .foldLeft(0, new SparseArray()) {
            case ((i, sparseArray), value) =>
              sparseArray.set(i, value)
              i + 1 -> sparseArray
          }
          ._2
    }

  }

  case class CleansedEmail(
    id: String,
    data: Array[Double],
    `type`: EmailType,
    htmlTags: Map[String, Int],
    replyTo: Option[ActorRef] = None)

  case class TrainSeq(
    seq: Seq[CleansedEmail],
    models: Seq[ModelType] = Seq.empty)
      extends ModelMessages

  case class Train(data: CleansedEmail)

  case class FeatureSizeForBayes(size: Int) extends ModelMessages

  case class Predict(data: CleansedEmail) extends ModelMessages

  case object Trained extends ModelMessages

  case object LastPredictionMade extends ModelMessages

  case object WriteModels extends ModelMessages

  private[model] case object ForgotModel extends ModelMessages

  sealed trait ModelMessages

}
