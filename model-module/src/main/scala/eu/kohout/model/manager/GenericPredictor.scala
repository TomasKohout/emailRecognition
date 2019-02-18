package eu.kohout.model.manager
import akka.actor.{Actor, Props}
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.messages.ModelMessages.{CleansedEmail, PredictResult, UpdateModel}
import eu.kohout.model.manager.traits.Predictor
import smile.classification.Classifier

object GenericPredictor {
  val name: String => String = _ + "Predictor"
  def props: Props = Props(new GenericPredictor)
}

class GenericPredictor extends Actor with Predictor {

  override val log = Logger(context.self.path.name)

  override def receive: Receive = {
    case updateModel: UpdateModel =>
      serializer.fromXML(updateModel.model) match {
        case classifier: Classifier[_] =>
          log.debug("Model received and updated!")
          model = classifier.asInstanceOf[Classifier[Array[Double]]]
        case other =>
          log.error(s"received something that is not classifier for Array[Double =>> {}", other)
      }

    case predict: CleansedEmail =>
      val replyTo = sender()

      replyTo ! PredictResult(
        id = predict.id,
        result = model.predict(predict.data),
        `type` = predict.`type`
      )
  }
}
