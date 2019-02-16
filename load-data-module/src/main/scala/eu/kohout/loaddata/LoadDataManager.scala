package eu.kohout.loaddata
import java.io.File

import akka.actor.{Actor, ActorRef, Props}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.loaddata.LoadDataWorker.LoadData
import eu.kohout.parser.EmailType

import scala.io.Source

object LoadDataManager {

  def props(
    config: Config,
    cleanDataManager: ActorRef
  ): Props = Props(new LoadDataManager(config, cleanDataManager))

  case class LoadDataFromPath(path: File) extends LoadDataManagerMessages

  sealed trait LoadDataManagerMessages

  private object Configuration {
    val configPath = "loadDataManager"
    val numberOfWorkersPath = s"$configPath.numberOfWorkers"
    val dataPath = s"$configPath.data"
    val labelsPath = s"$configPath.labels"
  }

  private type LoadDataWorkers = ActorRef

  private type FileName = String
}

class LoadDataManager(
  config: Config,
  cleanDataManager: ActorRef)
    extends Actor {
  import LoadDataManager._

  private val log = Logger(getClass)

  private var workers: Router = _
  private var emailTypes: Map[FileName, EmailType] = _

  private def createWorkers(): Unit = {
    val numberOfWorkers = config.getInt(Configuration.numberOfWorkersPath)
    require(numberOfWorkers > 0, "At least one worker for loading data must be created!")

    val workerName = "LoadDataWorker"
    val routees = Vector.fill(numberOfWorkers) {

      val worker = context.actorOf(LoadDataWorker.props(cleanDataManager))
      context watch worker
      ActorRefRoutee(worker)
    }

    workers = Router(RoundRobinRoutingLogic(), routees)
  }

  override def receive: Receive = {
    case loadData: LoadDataFromPath =>
      loadData.path
        .listFiles()
        .flatMap(file => emailTypes.get(file.getName).map((_, file)))
        .foreach { case (emailType, file) => workers.route(LoadData(email = file, label = emailType), self) }
  }

  private def splitLabelsRow(row: String): Option[(FileName, EmailType)] = {
    val index = row.indexOf(" ")
    if (index > 0) {
      val (emailType, path) = row.splitAt(index)

      val lastIndex = path.lastIndexOf("/")

      val fileName = if (lastIndex > 0 && lastIndex < path.length) {
        Some(path.substring(lastIndex, path.length))
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

    createWorkers()

    require(config.hasPath(Configuration.dataPath), s"This `${Configuration.dataPath}` can not be empty.")
    require(config.hasPath(Configuration.labelsPath), s"This `${Configuration.labelsPath}` can not be empty.")

    val emailsDir = new File(config.getString(Configuration.dataPath))
    require(!emailsDir.exists || !emailsDir.isDirectory, s"Provided path is not a directory. ${Configuration.dataPath}")

    val labelsFile = new File(config.getString(Configuration.labelsPath))
    require(!labelsFile.exists || !labelsFile.isFile, s"Provided path is not a file. ${Configuration.labelsPath}")

    emailTypes = createLabelMap(labelsFile)

    self ! LoadDataFromPath(emailsDir)
  }
}
