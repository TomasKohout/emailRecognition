package eu.kohout.model.manager

import java.io.{File, FileWriter}

import akka.actor.{Actor, ActorRef, Props}
import akka.routing.Broadcast
import com.typesafe.scalalogging.Logger
import ModelMessages._
import eu.kohout.model.manager.traits.Trainer
import smile.classification.{NaiveBayes, OnlineClassifier, SVM}

object GenericTrainer {
  val name: String => String = _ + "Trainer"

  def props(
    model: Unit => OnlineClassifier[Array[Double]],
    predictors: ActorRef,
    writeModelTo: String
  ): Props =
    Props(new GenericTrainer(modelCreator = model, predictors = predictors, writeModelTo))
}

class GenericTrainer(
  modelCreator: Unit => OnlineClassifier[Array[Double]],
  predictors: ActorRef,
  writeModelTo: String)
    extends Actor
    with Trainer {
  override val log: Logger = Logger(self.path.toStringWithoutAddress)
  private var version = 0

  override var model: OnlineClassifier[Array[Double]] = modelCreator(())

  override def receive: Receive = {
    case TrainData(data) =>
      val replyTo = sender()
      val classifiers = data
        .map(_.`type`.y)(collection.breakOut[Seq[CleansedEmail], Int, List[Int]])

      log.debug("Learning!")

      data.map(_.data).foldLeft(classifiers){
        case (Nil, x) =>
          Nil
        case (y :: Nil, x) =>
          model.learn(x, y)
          Nil
        case (y :: ys, x ) =>
          model.learn(x, y)
          ys
      }

      model match {
        case svm: SVM[Array[Double]] =>
          log.debug("Finishing svm learning!")
          svm.finish()
        case _ => ()
      }


      replyTo ! Trained
      predictors ! Broadcast(UpdateModel(serializer.toXML(model)))

    case WriteModels =>
      val xmlModel = serializer.toXML(model)
      val name = model match {
        case _: NaiveBayes => "NaiveBayes"

        case _: SVM[Array[Double]] => "SVM"

        case _ => "other"
      }

      val writer = new FileWriter(new File(writeModelTo + "/" + name + version))
      try {
        writer.write(xmlModel)
      } finally {
        writer.close()
        version = version + 1
      }

    case ForgotModel =>
      model = modelCreator(())
  }

  override def preStart(): Unit =
    super.preStart()
}
