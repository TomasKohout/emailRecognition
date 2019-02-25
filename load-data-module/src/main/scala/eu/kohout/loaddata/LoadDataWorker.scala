package eu.kohout.loaddata
import java.io.File

import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager.TestData
import eu.kohout.parser.{Email, EmailParser, EmailType}
import eu.kohout.types.ResultsAggreagtorMessages.BeforePrediction

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

  case class LoadTrainData(
    email: File,
    label: EmailType)
      extends LoadDataWorkerMessage

  case class LoadedTrainData(
    email: Email,
    file: String)
      extends LoadDataWorkerMessage

  case class DecreaseForError(exception: Throwable) extends LoadDataWorkerMessage

  sealed trait LoadDataWorkerMessage
}

class LoadDataWorker(
  val cleanDataManager: ActorRef,
  resultsAggregator: ActorRef)
    extends Actor {
  import LoadDataWorker._

  private val log = Logger(self.path.toStringWithoutAddress)

  override def receive: Receive = {
    case loadData: LoadTrainData =>
      log.debug("Load train data message received {}, from {}", loadData.email.getName, sender().path.address.toString)
      sender() ! Try(
        LoadedTrainData(EmailParser.parseFromFile(loadData.email.getAbsolutePath, loadData.label), loadData.email.getAbsolutePath)
      ).transform[LoadDataWorkerMessage](
          { data: LoadedTrainData =>
            Success(data)
          }, { exception: Throwable =>
            Success(DecreaseForError(exception))
          }
        )
        .get
    case loadData: LoadTestData =>
      log.debug("Load test data message received {}, from {}", loadData.email.getName, sender().path.address.toString)
      val data = TestData(EmailParser.parseFromFile(loadData.email.getAbsolutePath, loadData.label))

      resultsAggregator ! BeforePrediction(data.email.id, data.email.`type`.name)
      cleanDataManager ! data
  }
}
