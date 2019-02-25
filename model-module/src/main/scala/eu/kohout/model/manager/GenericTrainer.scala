package eu.kohout.model.manager
import akka.actor.{Actor, ActorRef, Props}
import akka.routing.Broadcast
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.messages.ModelMessages.{CleansedEmail, Share, TrainSeq, UpdateModel}
import eu.kohout.model.manager.traits.Trainer
import smile.classification.{NaiveBayes, OnlineClassifier, SVM}
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
  override val log: Logger = Logger(self.path.toStringWithoutAddress)

  override def receive: Receive = {
    case data: TrainSeq =>
      val classifiers = data.seq
        .map(_.`type`.y)(collection.breakOut[Seq[CleansedEmail], Int, Array[Int]])

      model match {
        case naive: NaiveBayes =>
          log.info("Learning naive bayes!")
          naive.learn(data.seq.map(_.data).toArray, classifiers)
        case svm: SVM[Array[Double]] =>
          log.debug("Learning!")
          svm.learn(data.seq.map(_.data).toArray, classifiers)
          svm.finish()
      }

      self ! Share

    case Share =>
      log.info("Sharing model to predictors")
      val updateModel = UpdateModel(serializer.toXML(model))
      predictors ! Broadcast(updateModel)

  }

  override def preStart(): Unit =
    super.preStart()
}
