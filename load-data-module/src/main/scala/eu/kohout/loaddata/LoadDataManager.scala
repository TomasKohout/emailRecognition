package eu.kohout.loaddata

import java.io.File

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager.{CleanDataForDictionary, TrainData}
import eu.kohout.loaddata.LoadDataWorker.LoadTestData
import eu.kohout.parser.Email

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object LoadDataManager {

  val name = "LoadData"

  def props(
    config: Config,
    cleanDataManager: ActorRef,
    resultsAggregator: ActorRef
  ): Props = Props(new LoadDataManager(config, cleanDataManager, resultsAggregator))

  private type LoadDataWorkers = ActorRef

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

  private def dictionaryExists: Receive = {
    case StartCrossValidation =>
      log.info("Starting cross validation")
      context.become(crossValidation)
      self ! StartCrossValidation

    case _ =>
      ()
//    case decreaseForError: DecreaseForError =>
//      log.error("Error occured: ", decreaseForError.exception.getMessage)
//      decreaseForError.exception.printStackTrace()
//
//      howManyEmailsToLoad = howManyEmailsToLoad - 1
//      log.debug("sizeOfTrainData {}", howManyEmailsToLoad)
//      if (howManyEmailsToLoad == 0) {
//        cleanDataManager ! TrainData(soFarLoadedData)
//        context.system.scheduler.scheduleOnce(3 minutes, self, LoadTestData)
//        soFarLoadedData = Seq.empty
//      }
//      ()
//
//    case loadedData: LoadedData =>
//      soFarLoadedData = loadedData.email +: soFarLoadedData
//      howManyEmailsToLoad = howManyEmailsToLoad - 1
//
//      log.debug("sizeOfTrainData {}", howManyEmailsToLoad)
//      if (howManyEmailsToLoad == 0) {
//        cleanDataManager ! TrainData(soFarLoadedData)
//        context.system.scheduler.scheduleOnce(4 minutes, self, LoadTestData)
//        soFarLoadedData = Seq.empty
//      }
//
//      ()
//
//    case LoadData =>
//      log.debug("Loading data from path: {}", emailsDir.getAbsolutePath)
//
//      val files = splitedFiles match {
//        case Nil =>
//          log.info("Cross validation has been completely done.")
//          Array.empty
//        case x :: Nil =>
//          splitedFiles = Nil
//          x
//        case x :: xs =>
//          splitedFiles = xs
//          x
//      }
//
//      val groupedByType = files
//        .flatMap { file =>
//          emailTypes
//            .get(file.getName)
//            .map((_, file))
//        } //group by email Type
//        .groupBy(_._1)
//
//      val hamMails = groupedByType.get(Ham)
//      val spamMails = groupedByType.get(Spam)
//
//      //TODO this could be problem. Data sets does not have to be the same size
//      val (spamTrain, spamTest) = spamMails.getOrElse(Array.empty).splitAt(takeAmountForTraining(files.length))
//      val (hamTrain, hamTest) = hamMails.getOrElse(Array.empty).splitAt(takeAmountForTraining(files.length))
//
//      val trainData = spamTrain ++ hamTrain
//
//      testDataPaths = spamTest.map(_._2) ++ hamTest.map(_._2)
//
//      howManyEmailsToLoad = trainData.length
//
//      log.debug("Initial size of train data {}", howManyEmailsToLoad)
//
//      trainData
//        .foreach {
//          case (emailType, file) =>
//            workers ! LoadDataWorker.LoadData(email = file, label = emailType)
//        }
//      ()
//
//    case LoadTestData =>
//      testDataPaths
//        .flatMap { file =>
//          emailTypes.get(file.getName).map((_, file))
//        }
//        .foreach {
//          case (emailType, file) =>
//            workers ! LoadDataWorker.LoadData(email = file, label = emailType, sendNext = true)
//        }
  }
  //

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
      log.debug("files length {}", testFiles.length)

      val trainFiles = allFiles.diff(testFiles)

      testDataPaths = testFiles

      log.debug("after filtering, test length {}, train length {}", testFiles.length, trainFiles.length)

      howManyEmailsToLoad = trainFiles.length

      log.debug("Initial size of train data {}", howManyEmailsToLoad)

      sendLoadedFiles(trainFiles, sendNext = false)

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

  def sendLoadedFiles(files: Array[File], sendNext: Boolean): Unit = {
    files.flatMap(file => emailTypes.get(file.getName).map((_, file)))
      .foreach {
        case (label, file) =>
          workers ! LoadDataWorker.LoadData(
            email = file,
            label = label,
            sendNext = sendNext
          )
      }
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
      sendToCleaningAfter = sendToCleaningAfter.flatMap{c =>
        c.cancel()
        None
      }
      cleanDataManager ! TrainData(soFarLoadedData)
      soFarLoadedData = Seq.empty
    }
  }

  private def creationOfDictionary: Receive = {
    case DictionaryExists =>
      context.become(dictionaryExists)
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
      log.error("Error occured: {}, stack: {}", decreaseForError.exception.map(_.getMessage), decreaseForError.exception.map(_.getStackTrace))

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
