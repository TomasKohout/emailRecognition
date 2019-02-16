package eu.kohout.model.manager
import akka.actor.Actor
import eu.kohout.model.manager.ModelManager.{Predict, Train}
import eu.kohout.parser.EmailType

object ModelManager {

  case class CleansedEmail(
    id: String,
    data: Array[Double],
    `type`: EmailType,
    htmlTags: Map[String, Int])

  case class Train(data: CleansedEmail) extends ModelMessages

  case class Predict(data: CleansedEmail) extends ModelMessages

  sealed trait ModelMessages
}

class ModelManager extends Actor {

  override def receive: Receive = {
    case message: Train   =>
    case message: Predict =>
  }

  override def preStart(): Unit =
    super.preStart()
}
