package eu.kohout.loaddata
import java.io.File

import akka.actor.{Actor, ActorRef}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.routing.RoundRobinPool
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.loaddata.LoadDataManagerLogic.Configuration
import eu.kohout.parser.{Email, EmailType}

import scala.io.Source

object LoadDataManagerLogic {

  object Configuration {
    val configPath = "load-data"
    val numberOfWorkersPath = "number-of-workers"
    val dataPath = "data"
    val labelsPath = "labels"
    val trainDataSize = "train-size"
    val splitTo = "split-to"
  }

}

trait LoadDataManagerLogic extends Actor {
  val config: Config
  val cleanDataManager: ActorRef
  val resultsAggregator: ActorRef

  protected val workers: ActorRef = createWorkers()

  protected val takeAmountForTraining: Int => Int = _ / 100 * config.getInt(Configuration.trainDataSize)

  protected def splitTo: Int = config.getInt(Configuration.splitTo)

  protected val emailsDir = new File(config.getString(Configuration.dataPath))
  require(emailsDir.exists && emailsDir.isDirectory, s"Provided path is not a directory. ${emailsDir.getAbsolutePath}")

  protected type FileName = String
  protected def log: Logger = Logger(getClass)

  protected var splitedFiles: List[Array[File]]
  protected val allFiles: Array[File]

  protected var testDataPaths: Array[File] = Array.empty

  // map that hold information about type (spam/ham) of email stored in specific file
  protected var emailTypes: Map[FileName, EmailType] = _
  protected var soFarLoadedData: Seq[Email] = Seq.empty
  protected var howManyEmailsToLoad = 0

  protected def splitLabelsRow(row: String): Option[(FileName, EmailType)] = {
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

  protected def createWorkers(): ActorRef = {
    val numberOfWorkers = config.getInt(Configuration.numberOfWorkersPath)
    require(numberOfWorkers > 0, "At least one worker for loading data must be created!")

    context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(numberOfWorkers),
        ClusterRouterPoolSettings(totalInstances = 100, maxInstancesPerNode = numberOfWorkers, allowLocalRoutees = true)
      ).props(LoadDataWorker.props(cleanDataManager, resultsAggregator)),
      name = "LoadDataWorker"
    )
  }

  protected def createLabelMap(path: File): Map[FileName, EmailType] =
    Source
      .fromFile(path.getAbsolutePath)
      .getLines
      .toSeq
      .flatMap(splitLabelsRow)(collection.breakOut[Seq[String], (FileName, EmailType), Map[FileName, EmailType]])

  protected def splitForCrossValidation(files: Array[File]): List[Array[File]] = {
    val sizeOfPart = files.length / splitTo

    log.debug("Size of part {}", sizeOfPart)
    def innerSplit(
      list: List[Array[File]],
      filesLeft: Array[File],
      soFarSplited: Int
    ): List[Array[File]] = {
      val (part, whatsLeft) = filesLeft.splitAt(sizeOfPart)

      if (splitTo == soFarSplited) part ++ whatsLeft :: list
      else innerSplit(part :: list, whatsLeft, soFarSplited + 1)
    }

    innerSplit(List.empty, files, 1)
  }

  override def preStart(): Unit = {
    super.preStart()

    require(config.hasPath(Configuration.dataPath), s"This `${Configuration.dataPath}` can not be empty.")
    require(config.hasPath(Configuration.labelsPath), s"This `${Configuration.labelsPath}` can not be empty.")

    val labelsFile = new File(config.getString(Configuration.labelsPath))
    require(labelsFile.exists && labelsFile.isFile, s"Provided path is not a file. ${Configuration.labelsPath}")

    emailTypes = createLabelMap(labelsFile)
    log.debug("Label map size {}", emailTypes.size)

  }
}
