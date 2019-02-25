package eu.kohout.model.manager.messages
import akka.actor.ActorRef
import eu.kohout.parser.EmailType
import eu.kohout.types.ModelTypes
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
    models: Seq[ModelTypes] = Seq.empty)
      extends ModelMessages

  case class Train(data: CleansedEmail)

  case class FeatureSizeForBayes(size: Int) extends ModelMessages

  case class Predict(data: CleansedEmail) extends ModelMessages

  case class Trained(id: String) extends ModelMessages

  sealed trait ModelMessages

}
