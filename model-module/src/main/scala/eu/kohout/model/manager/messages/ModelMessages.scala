package eu.kohout.model.manager.messages
import eu.kohout.parser.EmailType

object ModelMessages {
  case object Share extends ModelMessages

  type Serialization = String
  case class UpdateModel(model: Serialization) extends ModelMessages

  case class PredictResult(
    id: String,
    result: Int,
    `type`: EmailType)
      extends ModelMessages

  case class CleansedEmail(
    id: String,
    data: Array[Double],
    `type`: EmailType,
    htmlTags: Map[String, Int])

  case class Train(data: CleansedEmail) extends ModelMessages

  case class Predict(data: CleansedEmail) extends ModelMessages

  sealed trait ModelMessages
}
