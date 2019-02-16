package eu.kohout.model.manager
import akka.actor.{Actor, Props}
import akka.routing.Router
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.messages.ModelMessages.{CleansedEmail, Share, UpdateModel}
import eu.kohout.model.manager.traits.Trainer
import smile.classification.OnlineClassifier

object GenericTrainer {
  val name: String => String = _ + "Trainer"

  def props(
    model: OnlineClassifier[Array[Double]],
    predictors: Router,
    shareAfter: Int = 1000
  ): Props =
    Props(new GenericTrainer(model = model, predictors = predictors, shareAfter = shareAfter))
}

class GenericTrainer(
  override val model: OnlineClassifier[Array[Double]],
  predictors: Router,
  override val shareAfter: Int)
    extends Actor
    with Trainer {
  override val log: Logger = Logger(self.path.name)

  override def receive: Receive = {
    case email: CleansedEmail =>
      model.learn(email.data, email.`type`.y)
      trainedTimes += 1
      if (trainedTimes >= shareAfter) self ! Share

    case Share =>
      val updateModel = UpdateModel(serializer.toXML(model))
      predictors.routees.foreach(_.send(updateModel, self))

  }

  override def preStart(): Unit =
    super.preStart()
}
