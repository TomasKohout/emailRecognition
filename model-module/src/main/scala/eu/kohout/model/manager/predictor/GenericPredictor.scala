package eu.kohout.model.manager.predictor

import akka.actor.{Actor, Props}
import com.thoughtworks.xstream.XStream
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.ModelMessages.{CleansedEmail, PredictResult, Trained, UpdateModel}
import smile.classification.Classifier

import scala.util.Try

object GenericPredictor {
  val name: String => String = _ + "Predictor"
  def props: Props = Props(new GenericPredictor)
}

class GenericPredictor extends Actor{
  var model: Option[Classifier[Array[Double]]] = None
  val serializer = new XStream

  val log = Logger(context.self.path.toStringWithoutAddress)

  override def receive: Receive = {
    case updateModel: UpdateModel =>
      serializer.fromXML(updateModel.model) match {
        case classifier: Classifier[_] =>
          log.debug("Model received and updated!")
          model = Some(classifier.asInstanceOf[Classifier[Array[Double]]])
        case other =>
          log.error(s"received something that is not classifier for Array[Double =>> {}", other)
      }
      sender() ! Trained

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
  }

  override def preRestart(
    reason: Throwable,
    message: Option[Any]
  ): Unit = {
    super.preRestart(reason, message)
    model.foreach(model => self ! UpdateModel(serializer.toXML(model)))
  }
}
