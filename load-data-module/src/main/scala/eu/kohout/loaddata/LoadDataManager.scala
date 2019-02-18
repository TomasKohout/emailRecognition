package eu.kohout.loaddata
import java.io.File
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster._
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.singleton.{
  ClusterSingletonManager,
  ClusterSingletonManagerSettings,
  ClusterSingletonProxy,
  ClusterSingletonProxySettings
}
import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope
import akka.routing.{ActorRefRoutee, ConsistentHashingPool, RoundRobinRoutingLogic, Router}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.loaddata.LoadDataWorker.LoadData
import eu.kohout.parser.EmailType

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
    cleanDataManager: ActorRef
  ): Props = Props(new LoadDataManager(config, cleanDataManager))

  case class LoadDataFromPath(path: File) extends LoadDataManagerMessages

  sealed trait LoadDataManagerMessages

  private object Configuration {
    val configPath = "load-data"
    val numberOfWorkersPath = s"$configPath.number-of-workers"
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

  private val workers: ActorRef = createWorkers()
  private var emailTypes: Map[FileName, EmailType] = _

  private def createWorkers(): ActorRef = {
    val numberOfWorkers = config.getInt(Configuration.numberOfWorkersPath)
    require(numberOfWorkers > 0, "At least one worker for loading data must be created!")

    context.actorOf(
      ClusterRouterPool(
        ConsistentHashingPool(numberOfWorkers),
        ClusterRouterPoolSettings(totalInstances = numberOfWorkers * 10, maxInstancesPerNode = numberOfWorkers, allowLocalRoutees = true)
      ).props(LoadDataWorker.props(cleanDataManager)),
      name = "LoadDataWorker"
    )
  }

  override def receive: Receive = {
    case loadData: LoadDataFromPath =>
      log.debug("Loading data from path: {}", loadData.path.getAbsolutePath)

      loadData.path
        .listFiles()
        .flatMap { file =>
          emailTypes.get(file.getName).map((_, file))
        }
        .foreach {
          case (emailType, file) =>
            workers.tell(ConsistentHashableEnvelope(LoadData(email = file, label = emailType), UUID.randomUUID()), self)
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
