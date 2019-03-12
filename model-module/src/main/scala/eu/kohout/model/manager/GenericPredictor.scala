package eu.kohout.model.manager

import akka.actor.{Actor, Props}
import com.typesafe.scalalogging.Logger
import ModelMessages.{CleansedEmail, ForgotModel, PredictResult, UpdateModel}
import eu.kohout.model.manager.traits.Predictor
import smile.classification.Classifier

import scala.util.Try

object GenericPredictor {
  val name: String => String = _ + "Predictor"
  def props: Props = Props(new GenericPredictor)
}

class GenericPredictor extends Actor with Predictor {

  override val log = Logger(context.self.path.toStringWithoutAddress)

  override def receive: Receive = {
    case updateModel: UpdateModel =>
      serializer.fromXML(updateModel.model) match {
        case classifier: Classifier[_] =>
          log.debug("Model received and updated!")
          model = Some(classifier.asInstanceOf[Classifier[Array[Double]]])
        case other =>
          log.error(s"received something that is not classifier for Array[Double =>> {}", other)
      }

    case predict: CleansedEmail =>
      val replyTo = sender()

      val result = Try(model.map(_.predict(predict.data)))
        .fold(
          throwable => {
            log.error("Error occurred in prediction", throwable)
            -1
          },
          _.fold(-1)(identity)
        )

      replyTo ! PredictResult(
        id = predict.id,
        result = result,
        `type` = predict.`type`
      )
    case ForgotModel =>
      model = None
  }

  override def preRestart(
    reason: Throwable,
    message: Option[Any]
  ): Unit = {
    super.preRestart(reason, message)
    self ! UpdateModel(serializer.toXML(model))
  }
}
