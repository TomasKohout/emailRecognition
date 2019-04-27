package eu.kohout.loaddata

import java.io.File

import akka.Done
import akka.actor.{ActorRef, Props}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.loaddata.LoadDataWorker.LoadDataWorkerMessage
import eu.kohout.parser.EmailType

import scala.concurrent.ExecutionContext

object LoadDataManager {

  val name = "LoadData"

  def props(
    config: Config,
    cleanDataManager: ActorRef,
    rootActor: ActorRef
  ): Props = Props(new LoadDataManager(config, cleanDataManager, rootActor))

  private type LoadDataWorkers = ActorRef

  case object CrossValidationDone extends LoadDataManagerMessages

  case object LoadTrainData extends LoadDataManagerMessages
  case object CreateDictionaryFromData extends LoadDataManagerMessages
  case object DictionaryExists extends LoadDataManagerMessages
  case object StartCrossValidation extends LoadDataManagerMessages
  case object ContinueCrossValidation extends LoadDataManagerMessages

  sealed trait LoadDataManagerMessages
}

class LoadDataManager(
  val config: Config,
  val cleanDataManager: ActorRef,
  val rootActor: ActorRef)
    extends LoadDataManagerLogic(config = config, cleanDataManager = cleanDataManager) {
  import LoadDataManager._

  override protected val log: Logger = Logger(self.path.toStringWithoutAddress)

  implicit private val ec: ExecutionContext = context.dispatcher

  private def waitingForOrders: Receive = {
    case LoadTrainData =>
      val grouped = emailTypes
        .groupBy(_._2)

      val ham = grouped.getOrElse(EmailType.Ham, Seq.empty).map(_._1)
      val spam = grouped.getOrElse(EmailType.Spam, Seq.empty).map(_._1)
      log.debug("Train size of ham {} and spam {}", ham.size, spam.size)
      val trainData =
        if (ham.size > spam.size)
          ham.take(takeAmountForTraining(ham.size)).toSet ++ spam.take(
            takeAmountForTraining((spam.size * ham.size.doubleValue / spam.size).toInt)
          )
        else
          ham
            .take(takeAmountForTraining((ham.size * spam.size.doubleValue / ham.size).toInt))
            .toSet ++ spam
            .take(
              takeAmountForTraining(spam.size)
            )

      val data = allFiles.filter(file => trainData.contains(file.getName))

      log.info("Train data size is {}", data.length)

      sendLoadedFiles(data, LoadDataWorker.LoadTrainData)

    case StartCrossValidation =>
      log.info("Starting cross validation")
      context.become(crossValidation)
      self ! StartCrossValidation

    case Done => throw new Exception ("Reseting actor")

    case other =>
      log.debug("unknown message received {}", other)
  }

  private def crossValidation: Receive = {
    case StartCrossValidation =>
      log.debug("Loading data from path: {}", emailsDir.getAbsolutePath)

      val testFiles = splitedFiles match {
        case Nil =>
          log.info("Cross validation has been completely done.")
          Seq.empty[File]
        case x :: Nil =>
          splitedFiles = Nil
          x
        case x :: xs =>
          splitedFiles = xs
          x
      }
      if (testFiles.nonEmpty) {
        log.debug("files length {}", testFiles.length)

        val trainFiles = allFiles.diff(testFiles)

        testDataPaths = testFiles

        log.debug(
          "after filtering, test length {}, train length {}",
          testFiles.length,
          trainFiles.length
        )

        sendLoadedFiles(trainFiles, LoadDataWorker.LoadTrainData)
      } else {
        rootActor ! LoadDataManager.CrossValidationDone

        context.become(waitingForOrders)
      }
    case ContinueCrossValidation =>
      sendLoadedFiles(testDataPaths, LoadDataWorker.LoadPredictionData)
      testDataPaths = Seq.empty

    case Done => throw new Exception ("Reseting actor")

    case other =>
      log.debug("unknown message received {}", other)

  }

  private def creationOfDictionary: Receive = {
    case DictionaryExists =>
      context become waitingForOrders

    case CreateDictionaryFromData =>
      val replyTo = sender()
      log.debug("Loading data from path: {} for dictionary", emailsDir.getAbsolutePath)

      emailsDir listFiles () flatMap { file =>
        emailTypes get file.getName map ((_, file))
      } foreach {
        case (emailType, file) =>
          workers.!(LoadDataWorker.LoadDataForDictionary(email = file, label = emailType))(replyTo)
      }

    case _ =>
      ()

  }

  override def receive: Receive = creationOfDictionary

  def sendLoadedFiles(
    files: Seq[File],
    message: (File, EmailType) => LoadDataWorkerMessage
  ): Unit =
    files
      .flatMap(file => emailTypes.get(file.getName).map((_, file)))
      .foreach {
        case (label, file) =>
          workers ! message(file, label)
      }

}
