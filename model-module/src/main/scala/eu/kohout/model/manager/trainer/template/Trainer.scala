package eu.kohout.model.manager.trainer.template
import java.io.{File, FileWriter}

import akka.actor.{Actor, ActorRef}
import akka.routing.Broadcast
import com.thoughtworks.xstream.XStream
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.ModelMessages._

trait Trainer[T] extends Actor {
  protected val predictors: ActorRef
  protected val countOfPredictors: Int
  protected val writeModelTo: String

  private val serializer = new XStream
  private var trainedTimes: Int = 0
  private var receivedResponse: Int = 0
  protected val log: Logger
  protected val name: String
  private var replyToTrained: Option[ActorRef] = None

  def trainModel: (Array[Array[Double]], Array[Int]) => T

  private var model: Option[T] = None

  override def receive: Receive = {
    case msg: TrainData =>
      replyToTrained = Some(sender())
      train(msg)

    case WriteModels =>
      writeModel(name)

    case Trained =>
      trainedTimes += 1
      log.debug("trainedTimes: {}", trainedTimes)
      if (trainedTimes == countOfPredictors) {
        replyToTrained.foreach(_ ! Trained)
      }
  }

  protected def train: TrainData => Unit = { trainData =>
    trainedTimes = 0
    val classifiers = trainData.data
      .map(_.`type`.y)(collection.breakOut[Seq[CleansedEmail], Int, Array[Int]])

    log.debug("Learning!")

    model = Some(trainModel(trainData.data.map(_.data).toArray, classifiers))

    model.foreach { trainedModel =>
      predictors ! Broadcast(UpdateModel(serializer.toXML(trainedModel)))
    }
  }

  protected def writeModel: String => Unit = { name =>
    model.foreach { trainedModel =>
      val xmlModel = serializer.toXML(trainedModel)

      val writer = new FileWriter(new File(writeModelTo + "/" + name + trainedTimes))
      try {
        writer.write(xmlModel)
      } finally {
        writer.close()
        trainedTimes = trainedTimes + 1
      }
    }
  }
}
