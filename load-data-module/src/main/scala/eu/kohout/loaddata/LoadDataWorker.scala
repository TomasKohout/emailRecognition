package eu.kohout.loaddata

import java.io.File

import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.Logger
import eu.kohout.aggregator.ResultsAggregator.BeforePrediction
import eu.kohout.cleandata.CleanDataManager
import eu.kohout.loaddata.LoadDataManager.{DecreaseForError, LoadDataManagerMessages, LoadedData}
import eu.kohout.parser.{EmailParser, EmailType}

import scala.util.{Success, Try}

object LoadDataWorker {

  def props(
    cleanDataManager: ActorRef,
    resultsAggregator: ActorRef
  ): Props = Props(new LoadDataWorker(cleanDataManager, resultsAggregator))

  case class LoadTestData(
    email: File,
    label: EmailType)
      extends LoadDataWorkerMessage

  case class LoadData(
    email: File,
    label: EmailType,
    sendNext: Boolean = false)
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
    case loadData: LoadData =>
      log.debug(
        "Load data message received {}, from {}",
        loadData.email.getName,
        sender().path.toStringWithoutAddress
      )

      val result = Try(
        LoadedData(
          EmailParser
            .parseFromFile(loadData.email.getAbsolutePath, loadData.label),
          loadData.email.getAbsolutePath
        )
      ).transform[LoadDataManagerMessages](
          data => {
            if (loadData.sendNext) {
              resultsAggregator ! BeforePrediction(
                data.email.id,
                data.email.`type`
              )
              cleanDataManager ! CleanDataManager.TestData(data.email)
            }

            Success(data)
          },
          exception => Success(DecreaseForError(Some(exception)))
        )
        .get

      if(!loadData.sendNext) sender() ! result

    case other =>
      log.debug("Unsuported message {}", other)
  }
}
