package eu.kohout.model.manager
import akka.actor.{Actor, ActorRef, Props}
import akka.routing.{Broadcast, Router}
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.messages.ModelMessages.{CleansedEmail, Share, UpdateModel}
import eu.kohout.model.manager.traits.Trainer
import smile.classification.{NaiveBayes, OnlineClassifier}
import CleansedEmail._

object GenericTrainer {
  val name: String => String = _ + "Trainer"

  def props(
    model: Unit => OnlineClassifier[Array[Double]],
    predictors: ActorRef,
    shareAfter: Int = 1000
  ): Props =
    Props(new GenericTrainer(model = model(()), predictors = predictors, shareAfter = shareAfter))
}

class GenericTrainer(
  override val model: OnlineClassifier[Array[Double]],
  predictors: ActorRef,
  override val shareAfter: Int)
    extends Actor
    with Trainer {
  override val log: Logger = Logger(self.path.name)

  override def receive: Receive = {
    case email: CleansedEmail =>
      model match {
        case naive: NaiveBayes =>
          log.info("Learning naive bayes!")
          naive.learn(email.data.toSparseArray, email.`type`.y)
        case other =>
          log.debug("Learning!")
          other.learn(email.data, email.`type`.y)
      }

      trainedTimes += 1
      if (trainedTimes >= shareAfter) {
        trainedTimes = 0
        self ! Share
      }

    case Share =>
      log.info("SHARE WITH OTHERS!")
      log.info(model.asInstanceOf[NaiveBayes].toString)
      val updateModel = UpdateModel(serializer.toXML(model))
      predictors ! Broadcast(updateModel)

  }

  override def preStart(): Unit =
    super.preStart()
}
