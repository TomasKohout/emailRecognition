package eu.kohout.loaddata
import java.io.File

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.routing.RoundRobinPool
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.loaddata.LoadDataWorker.{DecreaseForError, LoadedTrainData}
import eu.kohout.parser.{Email, EmailType}
import eu.kohout.cleandata.CleanDataManager.TrainData
import eu.kohout.parser.EmailType.{Ham, Spam}
import eu.kohout.types.Types.LoadTestData

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.io.Source

object LoadDataManager {

  val name = "LoadData"

  def asClusterSingleton(
    props: Props,
    appCfg: Config,
    system: ActorSystem
  ): ActorRef = {

    val singleton = system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = props,
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system)
      ),
      name = name + "Manager"
    )

    system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = singleton.path.toStringWithoutAddress,
        settings = ClusterSingletonProxySettings(system)
      ),
      name = name + "Proxy"
    )

  }

  def props(
    config: Config,
    cleanDataManager: ActorRef,
    resultsAggregator: ActorRef
  ): Props = Props(new LoadDataManager(config, cleanDataManager, resultsAggregator))

  case class LoadDataFromPath(path: File) extends LoadDataManagerMessages

  sealed trait LoadDataManagerMessages

  private object Configuration {
    val configPath = "load-data"
    val numberOfWorkersPath = s"$configPath.number-of-workers"
    val dataPath = s"$configPath.data"
    val labelsPath = s"$configPath.labels"
    val trainDataSize = s"$configPath.train-data-size"
    val sizeLimit = s"$configPath.size-limit"
  }

  private type LoadDataWorkers = ActorRef

  private type FileName = String
}

class LoadDataManager(
  config: Config,
  cleanDataManager: ActorRef,
  resultsAggregator: ActorRef)
    extends Actor {
  import LoadDataManager._

  private val log = Logger(self.path.toStringWithoutAddress)
  implicit private val ec: ExecutionContext = context.dispatcher
  // map that hold information about type (spam/ham) of email stored in specific file
  private var emailTypes: Map[FileName, EmailType] = _
  private var loadedTrainData: Seq[Email] = Seq.empty
  private var sizeOfTrainData = 0

  private val workers: ActorRef = createWorkers()

  private val takeAmountForTraining: Int => Int = _ / 100 * config.getInt(Configuration.trainDataSize)

  private val sizeLimit = config.getInt(Configuration.sizeLimit)

  private var testDataPaths: Array[File] = Array.empty

  private def createWorkers(): ActorRef = {
    val numberOfWorkers = config.getInt(Configuration.numberOfWorkersPath)
    require(numberOfWorkers > 0, "At least one worker for loading data must be created!")

    context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(numberOfWorkers),
        ClusterRouterPoolSettings(totalInstances = 100, maxInstancesPerNode = numberOfWorkers, allowLocalRoutees = true)
      ).props(LoadDataWorker.props(cleanDataManager, resultsAggregator))
        .withDispatcher("clean-dispatcher"),
      name = "LoadDataWorker"
    )
  }

  override def receive: Receive = {

    case decreaseForError: DecreaseForError =>
      log.error("Error occured: ", decreaseForError.exception.getMessage)
      decreaseForError.exception.printStackTrace()

      sizeOfTrainData = sizeOfTrainData - 1
      log.debug("sizeOfTrainData {}", sizeOfTrainData)
      if (sizeOfTrainData == 0) {
        cleanDataManager ! TrainData(loadedTrainData)
        context.system.scheduler.scheduleOnce(3 minutes, self, LoadTestData)
      }
      ()

    case loadedData: LoadedTrainData =>
      loadedTrainData = loadedData.email +: loadedTrainData
      sizeOfTrainData = sizeOfTrainData - 1

      log.debug("sizeOfTrainData {}", sizeOfTrainData)
      if (sizeOfTrainData == 0) {
        cleanDataManager ! TrainData(loadedTrainData)
        context.system.scheduler.scheduleOnce(4 minutes, self, LoadTestData)
      }

      ()

    case loadData: LoadDataFromPath =>
      log.debug("Loading data from path: {}", loadData.path.getAbsolutePath)

      val files = loadData.path
        .listFiles()
        .take(sizeLimit)

      val groupedByType = files
        .flatMap { file =>
          emailTypes
            .get(file.getName)
            .map((_, file))
        } //group by email Type
        .groupBy(_._1)

      val hamMails = groupedByType.get(Ham)
      val spamMails = groupedByType.get(Spam)

      //TODO this could be problem. Data sets does not have to be the same size
      val (spamTrain, spamTest) = spamMails.getOrElse(Array.empty).splitAt(takeAmountForTraining(files.length))
      val (hamTrain, hamTest) = hamMails.getOrElse(Array.empty).splitAt(takeAmountForTraining(files.length))

      val trainData = spamTrain ++ hamTrain

      testDataPaths = spamTest.map(_._2) ++ hamTest.map(_._2)

      sizeOfTrainData = trainData.length

      log.debug("Initial size of train data {}", sizeOfTrainData)

      trainData
        .foreach {
          case (emailType, file) =>
            workers ! LoadDataWorker.LoadTrainData(email = file, label = emailType)
        }
      ()

    case LoadTestData =>
      testDataPaths
        .flatMap { file =>
          emailTypes.get(file.getName).map((_, file))
        }
        .foreach {
          case (emailType, file) =>
            workers ! LoadDataWorker.LoadTestData(email = file, label = emailType)
        }
  }

  private def splitLabelsRow(row: String): Option[(FileName, EmailType)] = {
    val index = row.indexOf(" ")
    if (index > 0) {
      val (emailType, path) = row.splitAt(index)

      val lastIndex = path.lastIndexOf("/")

      val fileName = if (lastIndex > 0 && lastIndex < path.length) {
        Some(path.substring(lastIndex, path.length).replace("/", ""))
      } else {
        log.warn("{} does not satisfy label definitions", path)
        None
      }

      fileName.flatMap(name => EmailType.fromString(emailType).map((name, _)))
    } else {
      None
    }
  }

  private def createLabelMap(path: File): Map[FileName, EmailType] =
    Source
      .fromFile(path.getAbsolutePath)
      .getLines
      .toSeq
      .flatMap(splitLabelsRow)(collection.breakOut[Seq[String], (FileName, EmailType), Map[FileName, EmailType]])

  override def preStart(): Unit = {
    super.preStart()

    require(config.hasPath(Configuration.dataPath), s"This `${Configuration.dataPath}` can not be empty.")
    require(config.hasPath(Configuration.labelsPath), s"This `${Configuration.labelsPath}` can not be empty.")

    val emailsDir = new File(config.getString(Configuration.dataPath))
    require(emailsDir.exists && emailsDir.isDirectory, s"Provided path is not a directory. ${emailsDir.getAbsolutePath}")

    val labelsFile = new File(config.getString(Configuration.labelsPath))
    require(labelsFile.exists && labelsFile.isFile, s"Provided path is not a file. ${Configuration.labelsPath}")

    emailTypes = createLabelMap(labelsFile)
    log.debug("Label map size {}", emailTypes.size)

    log.debug("Sending message to self")
    self ! LoadDataFromPath(emailsDir)
  }
}
