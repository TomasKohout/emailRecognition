package eu.kohout.loaddata

import java.io.File

import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.Logger
import eu.kohout.aggregator.ResultsAggregator.BeforePrediction
import eu.kohout.cleandata.CleanDataManager
import eu.kohout.cleandata.CleanDataManager.{CleanDataForDictionary, PredictionData, TrainData}
import eu.kohout.loaddata.LoadDataManager.{DecreaseForError, LoadDataManagerMessages, LoadedData}
import eu.kohout.parser.{EmailParser, EmailType}

import scala.util.{Success, Try}

object LoadDataWorker {

  def props(
    cleanDataManager: ActorRef,
    resultsAggregator: ActorRef
  ): Props = Props(new LoadDataWorker(cleanDataManager, resultsAggregator))

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
  val cleanDataManager: ActorRef,
  resultsAggregator: ActorRef)
    extends Actor {
  import LoadDataWorker._

  private val log = Logger(self.path.toStringWithoutAddress)

  override def receive: Receive = {
    case loadData: LoadDataForDictionary =>
      log.debug("Load data for dictionary received {}", loadData.email.getName)

      val replyTo = sender()

      Try(
        LoadedData(
          EmailParser
            .parseFromFile(loadData.email.getAbsolutePath, loadData.label),
          loadData.email.getAbsolutePath
        )
      ) fold (
        exception => {
          log error ("Exception occurred when loading data.", exception)
          None
        },
        data => Some(data.email)
      ) foreach (data => cleanDataManager.!(CleanDataManager.CleanDataForDictionary(data))(replyTo))

    case loadData: LoadTrainData =>
      log.debug(
        "Load train data message received {}, from {}",
        loadData.email.getName,
        sender().path.toStringWithoutAddress
      )

      Try(
        TrainData(
          EmailParser
            .parseFromFile(loadData.email.getAbsolutePath, loadData.label)
        )
      ).fold(
        exception => log.error("Exception occurred when loading data", exception),
        data => cleanDataManager ! CleanDataManager.TrainData(data.email)
      )

    case message: LoadPredictionData =>
      log.debug(
        "Load test data message received {}, from {}",
        message.email.getName,
        sender().path.toStringWithoutAddress
      )

      Try(
        PredictionData(
          EmailParser
            .parseFromFile(message.email.getAbsolutePath, message.label)
        )
      ).fold(
        exception => log.error("Exception occurred when loading data", exception),
        data => {
          resultsAggregator ! BeforePrediction(id = data.email.id, `type` = data.email.`type`)
          cleanDataManager ! CleanDataManager.PredictionData(data.email)
        }
      )

    case other =>
      log.debug("Unsuported message {}", other)
  }
}
