package eu.kohout.model.manager
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
    `type`: EmailType)

  case class Train(data: CleansedEmail) extends ModelMessages

  case class FeatureSizeForBayes(size: Int) extends ModelMessages

  case class Predict(data: CleansedEmail) extends ModelMessages

  case object Trained extends ModelMessages

  case object LastPredictionMade extends ModelMessages

  case object WriteModels extends ModelMessages

  case object SwitchToPrediction extends ModelMessages

  case object SetShiftMessage extends ModelMessages

  private[model] case object TrainModels extends ModelMessages
  private[model] case class TrainData(data: Seq[CleansedEmail]) extends ModelMessages

  sealed trait ModelMessages

}
