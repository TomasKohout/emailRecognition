package eu.kohout.model.manager.messages
import eu.kohout.parser.EmailType
import smile.math.SparseArray

object ModelMessages {
  case object Share extends ModelMessages

  type Serialization = String
  case class UpdateModel(model: Serialization) extends ModelMessages

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
    htmlTags: Map[String, Int])

  case class Train(data: CleansedEmail) extends ModelMessages

  case class Predict(data: CleansedEmail) extends ModelMessages

  sealed trait ModelMessages

  trait HttpMessage
}
