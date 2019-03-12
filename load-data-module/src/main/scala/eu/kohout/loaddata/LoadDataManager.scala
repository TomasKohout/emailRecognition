package eu.kohout.loaddata

import java.io.File

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.cluster.sharding.ShardRegion
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager.{CleanDataForDictionary, TrainData}
import eu.kohout.parser.{Email, EmailType}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object LoadDataManager {

  val name = "LoadData"

  val idExtractor: ShardRegion.ExtractEntityId = {
    case msg => (name, msg)
  }

  val shardResolver: ShardRegion.ExtractShardId =
    _ => (math.abs(name.hashCode) % 100).toString

  def props(
    config: Config,
    cleanDataManager: ActorRef,
    resultsAggregator: ActorRef
  ): Props = Props(new LoadDataManager(config, cleanDataManager, resultsAggregator))

  private type LoadDataWorkers = ActorRef

  case object LoadTrainData extends LoadDataManagerMessages
  case object CreateDictionaryFromData extends LoadDataManagerMessages
  case object LoadData extends LoadDataManagerMessages
  case object DictionaryExists extends LoadDataManagerMessages
  case object StartCrossValidation extends LoadDataManagerMessages
  case object ContinueCrossValidation extends LoadDataManagerMessages

  case class DecreaseForError(exception: Option[Throwable]) extends LoadDataManagerMessages

  case class LoadedData(
    email: Email,
    file: String)
      extends LoadDataManagerMessages

  sealed trait LoadDataManagerMessages
}

class LoadDataManager(
  val config: Config,
  val cleanDataManager: ActorRef,
  val resultsAggregator: ActorRef)
    extends Actor
    with LoadDataManagerLogic {
  import LoadDataManager._

  override protected val log: Logger = Logger(self.path.toStringWithoutAddress)

  override protected var splitedFiles: List[Array[File]] = splitForCrossValidation(emailsDir.listFiles())

  override protected val allFiles: Array[File] = splitedFiles.flatMap(_.map(identity)).toArray

  implicit private val ec: ExecutionContext = context.dispatcher

  private var sendToCleaningAfter: Option[Cancellable] = None

  private var dictionaryResolver: LoadDataWorkers = _

  private def loadTrainData: Receive = {
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
          ham.take(takeAmountForTraining((ham.size * spam.size.doubleValue / ham.size).toInt)).toSet ++ spam
            .take(
              takeAmountForTraining(spam.size)
            )

      val data = allFiles.filter(file => trainData.contains(file.getName))
      howManyEmailsToLoad = data.length
      log.info("Train data size is {}", data.length)
      soFarLoadedData = Seq.empty
      sendLoadedFiles(data, sendNext = false)

    case decreaseForError: DecreaseForError =>
      log.error("Error occured: {}", decreaseForError.exception.map(_.getMessage))
      acceptLoadMessage()

    case loadedData: LoadedData =>
      soFarLoadedData = loadedData.email +: soFarLoadedData
      acceptLoadMessage()

    case other =>
      log.info("blaaaaaaaa {}", other)
  }
  private def waitingForOrders: Receive = {
    case LoadTrainData =>
      log.info("Switching to loading train data")
      context.become(loadTrainData)
      self ! LoadTrainData
    case StartCrossValidation =>
      log.info("Starting cross validation")
      context.become(crossValidation)
      self ! StartCrossValidation

    case _ =>
      ()
  }

  private def crossValidation: Receive = {
    case StartCrossValidation =>
      log.debug("Loading data from path: {}", emailsDir.getAbsolutePath)

      val testFiles = splitedFiles match {
        case Nil =>
          log.info("Cross validation has been completely done.")
          Array.empty[File]
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

        howManyEmailsToLoad = trainFiles.length

        log.debug("Initial size of train data {}", howManyEmailsToLoad)

        sendLoadedFiles(trainFiles, sendNext = false)
      } else {
        context.become(waitingForOrders)
      }
    case ContinueCrossValidation =>
      sendLoadedFiles(testDataPaths, sendNext = true)

      testDataPaths = Array.empty
    case decreaseForError: DecreaseForError =>
      log.error("Error occured: ", decreaseForError.exception.map(_.getMessage))
      acceptLoadMessage()

    case loadedData: LoadedData =>
      soFarLoadedData = loadedData.email +: soFarLoadedData
      acceptLoadMessage()

    case _ =>
      ()

  }

  def sendLoadedFiles(
    files: Array[File],
    sendNext: Boolean
  ): Unit =
    files
      .flatMap(file => emailTypes.get(file.getName).map((_, file)))
      .foreach {
        case (label, file) =>
          workers ! LoadDataWorker.LoadData(
            email = file,
            label = label,
            sendNext = sendNext
          )
      }

  def acceptLoadMessage(): Unit = {
    howManyEmailsToLoad = howManyEmailsToLoad - 1
    sendToCleaningAfter = sendToCleaningAfter.fold(Some(context.system.scheduler.scheduleOnce(1 second, self, DecreaseForError(None)))) {
      cancelable =>
        cancelable.cancel()
        Some(context.system.scheduler.scheduleOnce(1 second, self, DecreaseForError(None)))
    }
    log.debug("sizeOfTrainData {}", howManyEmailsToLoad)
    if (howManyEmailsToLoad == 0) {
      sendToCleaningAfter = sendToCleaningAfter.flatMap { c =>
        c.cancel()
        None
      }
      cleanDataManager ! TrainData(soFarLoadedData)
      soFarLoadedData = Seq.empty
    }
  }

  private def creationOfDictionary: Receive = {
    case DictionaryExists =>
      context.become(waitingForOrders)
      self ! LoadData

    case CreateDictionaryFromData =>
      dictionaryResolver = sender()
      log.debug("Loading data from path: {} for dictionary", emailsDir.getAbsolutePath)

      howManyEmailsToLoad = emailsDir.listFiles().length

      emailsDir
        .listFiles()
        .flatMap { file =>
          emailTypes
            .get(file.getName)
            .map((_, file))
        }
        .foreach {
          case (emailType, file) =>
            workers ! LoadDataWorker.LoadData(email = file, label = emailType)
        }
      ()

    case decreaseForError: DecreaseForError =>
      log.error(
        "Error occured: {}, stack: {}",
        decreaseForError.exception.map(_.getMessage),
        decreaseForError.exception.map(_.getStackTrace)
      )

      howManyEmailsToLoad = howManyEmailsToLoad - 1
      log.debug("sizeOfTrainData {}", howManyEmailsToLoad)
      if (howManyEmailsToLoad == 0) {
        cleanDataManager ! CleanDataForDictionary(soFarLoadedData, dictionaryResolver)
        soFarLoadedData = Seq.empty
      }
      ()

    case loadedData: LoadedData =>
      soFarLoadedData = loadedData.email +: soFarLoadedData
      howManyEmailsToLoad = howManyEmailsToLoad - 1

      log.debug("sizeOfTrainData {}", howManyEmailsToLoad)
      if (howManyEmailsToLoad == 0) {
        cleanDataManager ! CleanDataForDictionary(soFarLoadedData, dictionaryResolver)
        soFarLoadedData = Seq.empty
      }

      ()
    case _ =>
      ()

  }

  override def receive: Receive = creationOfDictionary

}
