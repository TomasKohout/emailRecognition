package eu.kohout.loaddata

import java.io.File

import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager
import eu.kohout.parser.{Email, EmailParser, EmailType}

import scala.util.Try

object LoadDataWorker {

  def props(
    cleanDataManager: ActorRef
  ): Props = Props(new LoadDataWorker(cleanDataManager))

  case class LoadPredictionData(
    email: File,
    label: EmailType)
      extends LoadDataWorkerMessage

  case class LoadTrainData(
    email: File,
    label: EmailType)
      extends LoadDataWorkerMessage

  case class LoadDataForDictionary(
    email: File,
    label: EmailType)
      extends LoadDataWorkerMessage

  sealed trait LoadDataWorkerMessage
}

class LoadDataWorker(
  val cleanDataManager: ActorRef)
    extends Actor {
  import LoadDataWorker._

  private val log = Logger(self.path.toStringWithoutAddress)

  private def processMessage(
    email: File,
    label: EmailType,
    onSuccess: Email => Unit
  ): Unit =
    Try(
      EmailParser.parse(email, label)
    ).fold(
      exception => log.error("Exception occurred when loading data", exception),
      onSuccess
    )

  override def receive: Receive = {
    case LoadDataForDictionary(email, label) =>
      log.debug("Load data for dictionary received {}", email.getName)
      val replyTo = sender()
      processMessage(
        email,
        label,
        email => cleanDataManager.!(CleanDataManager.CleanDataForDictionary(email))(replyTo)
      )

    case LoadTrainData(email, label) =>
      log.debug(
        "Load train data message received {}",
        email.getName
      )

      processMessage(
        email,
        label,
        email => cleanDataManager ! CleanDataManager.TrainData(email)
      )

    case LoadPredictionData(email, label) =>
      log.debug(
        "Load test data message received {}",
        email.getName
      )
      processMessage(
        email,
        label,
        email => cleanDataManager ! CleanDataManager.PredictionData(email)
      )

    case other =>
      log.debug("Unsuported message {}", other)
  }
}
