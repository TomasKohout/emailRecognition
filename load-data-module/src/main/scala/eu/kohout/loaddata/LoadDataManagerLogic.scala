package eu.kohout.loaddata
import java.io.File

import akka.actor.{Actor, ActorRef}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.routing.RoundRobinPool
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.loaddata.LoadDataManagerLogic.Configuration
import eu.kohout.parser.EmailType

import scala.io.Source
import scala.util.Random

object LoadDataManagerLogic {

  object Configuration {
    val configPath = "load-data"
    val numberOfWorkersPath = "number-of-workers"
    val dataPath = "data"
    val labelsPath = "labels"
    val trainDataSize = "train-size"
    val splitTo = "split-to"
    val fromEachGroup = "from-each-group"
  }

}

abstract class LoadDataManagerLogic(config: Config, cleanDataManager: ActorRef) extends Actor {

  protected val workers: ActorRef = createWorkers()

  protected val takeAmountForTraining: Int => Int = _ / 100 * config.getInt(
    Configuration.trainDataSize
  )
  protected val fromEachGroup: Option[Int] =
    if (config.hasPath(Configuration.fromEachGroup))
      Some(config.getInt(Configuration.fromEachGroup))
    else None

  protected def splitTo: Int = config.getInt(Configuration.splitTo)

  protected val emailsDir = new File(config.getString(Configuration.dataPath))
  require(
    emailsDir.exists && emailsDir.isDirectory,
    s"Provided path is not a directory. ${emailsDir.getAbsolutePath}"
  )

  protected type FileName = String
  protected def log: Logger = Logger(getClass)

  // map that hold information about type (spam/ham) of email stored in specific file
  protected val emailTypes: Map[FileName, EmailType] = {
    require(
      config.hasPath(Configuration.dataPath),
      s"This `${Configuration.dataPath}` can not be empty."
    )
    require(
      config.hasPath(Configuration.labelsPath),
      s"This `${Configuration.labelsPath}` can not be empty."
    )

    val labelsFile = new File(config.getString(Configuration.labelsPath))
    require(
      labelsFile.exists && labelsFile.isFile,
      s"Provided path is not a file. ${Configuration.labelsPath}"
    )

    createLabelMap(labelsFile)
  }

  protected var splitedFiles: List[Seq[File]] = {
    val groupedEmails = emailsDir
      .listFiles()
      .toSeq
      .flatMap(file => emailTypes.get(file.getName).map((_, file)))
      .groupBy(_._1)

    val hams = Random.shuffle(groupedEmails.getOrElse(EmailType.Ham, Seq.empty).map(_._2))
    val spams = Random.shuffle(groupedEmails.getOrElse(EmailType.Spam, Seq.empty).map(_._2))

    val hamsShrinked = hams.take(fromEachGroup.getOrElse(hams.length))
    val spamsShrinked = spams.take(fromEachGroup.getOrElse(spams.length))

    splitForCrossValidation(
      hamsShrinked ++ spamsShrinked
    )
  }

  protected val allFiles: Seq[File] = splitedFiles.flatMap(_.map(identity))

  protected var testDataPaths: Seq[File] = Seq.empty

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
        ClusterRouterPoolSettings(
          totalInstances = 100,
          maxInstancesPerNode = numberOfWorkers,
          allowLocalRoutees = true,
          useRoles = Set("load-data")
        )
      ).props(LoadDataWorker.props(cleanDataManager)),
      name = "LoadDataWorker"
    )
  }

  protected def createLabelMap(path: File): Map[FileName, EmailType] ={
    val source = Source.fromFile(path.getAbsolutePath)
    try {
      source
        .getLines
        .toSeq
        .flatMap(splitLabelsRow)(
          collection.breakOut[Seq[String], (FileName, EmailType), Map[FileName, EmailType]]
        )
    } finally {
      source.close()
    }
  }

  protected def splitForCrossValidation(files: Seq[File]): List[Seq[File]] = {
    val sizeOfPart = files.length / splitTo / 2

    def innerSplit(
      list: List[Seq[File]],
      filesLeft: Seq[File],
      soFarSplited: Int
    ): List[Seq[File]] = {

      val groupedFiles = filesLeft.flatMap(file => emailTypes.get(file.getName).map((_, file))).groupBy(_._1)

      val (hams, hamsLeft) = groupedFiles.getOrElse(EmailType.Ham, Seq.empty).map(_._2).splitAt(sizeOfPart)
      val (spams, spamsLeft) = groupedFiles.getOrElse(EmailType.Spam, Seq.empty).map(_._2).splitAt(sizeOfPart)

      if (splitTo == soFarSplited) hams ++ spams ++ hamsLeft ++ spamsLeft :: list
      else innerSplit(hams ++ spams :: list, hamsLeft ++ spamsLeft, soFarSplited + 1)
    }

    innerSplit(List.empty, files, 1)
  }

}
