package eu.kohout.model.manager.traits

import java.io.{File, FileWriter}

import akka.actor.{Actor, ActorRef}
import akka.routing.Broadcast
import com.thoughtworks.xstream.XStream
import com.typesafe.scalalogging.Logger
import eu.kohout.model.manager.ModelMessages._

trait Trainer[T] extends Actor {
  protected val predictors: ActorRef
  protected val writeModelTo: String

  private val serializer = new XStream
  private var trainedTimes: Int = 0
  protected val log: Logger
  protected val name: String

  def trainModel: (Array[Array[Double]], Array[Int]) => T

  private var model: Option[T] = None

  override def receive: Receive = {
    case msg: TrainData =>
      train(msg, sender())

    case WriteModels =>
      writeModel(name)
  }

  protected def train: (TrainData, ActorRef) => Unit = { (trainData, replyTo) =>
    val classifiers = trainData.data
      .map(_.`type`.y)(collection.breakOut[Seq[CleansedEmail], Int, Array[Int]])

    log.debug("Learning!")

    model = Some(trainModel(trainData.data.map(_.data).toArray, classifiers))

    model.foreach { trainedModel =>
      replyTo ! Trained
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
