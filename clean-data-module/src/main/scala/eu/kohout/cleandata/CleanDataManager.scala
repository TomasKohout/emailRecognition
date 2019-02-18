package eu.kohout.cleandata
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.singleton.{
  ClusterSingletonManager,
  ClusterSingletonManagerSettings,
  ClusterSingletonProxy,
  ClusterSingletonProxySettings
}
import akka.routing.ConsistentHashingPool
import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager._
import eu.kohout.parser.Email
import smile.nlp.stemmer.{LancasterStemmer, PorterStemmer, Stemmer}

object CleanDataManager {
  val name: String = "CleanData"

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
    modelManager: ActorRef
  ): Props = Props(new CleanDataManager(config = config, modelManager = modelManager))

  private object Configuration {
    val configPath = "clean-data"
    val numberOfWorkersPath = s"$configPath.number-of-workers"
    val takeFeatures = s"$configPath.take-features"
    val stemmer = s"$configPath.stemmer"
    val stopWords = s"$configPath.stop-words"
  }

  case class EmailNotCleaned(id: String) extends CleanDataManagerMessages

  case class EmailCleanded(id: String) extends CleanDataManagerMessages

  sealed trait CleanDataManagerMessages

  case class EmailRecognitionRequest(text: String) extends HttpMessage
  case class EmailRecognitionResponse(
    label: Label,
    models: List[Model])
      extends HttpMessage
  case class Model(
    percent: Int,
    typeOfModel: ModelType)

  object ModelType {
    case object SVM extends ModelType
    case object NaiveBayes extends ModelType
  }

  sealed trait ModelType

  object Label {
    case object Spam extends Label
    case object Ham extends Label
  }

  sealed trait Label

  case class TrainRequest(
    modelType: Option[ModelType],
    data: List[TrainData])
      extends HttpMessage

  case class TrainData(
    label: Label,
    message: String)

  sealed trait HttpMessage
}

class CleanDataManager(
  config: Config,
  modelManager: ActorRef)
    extends Actor {

  private val log = Logger(self.path.address.toString)

  private val workers: ActorRef = createWorkers()

  override def receive: Receive = {
    case message: CleanDataManagerMessages =>
      message match {
        case email: Email =>
          log.debug("email received: ", email.id)
          workers.tell(ConsistentHashableEnvelope(email, email.id), self)

        case email: EmailCleanded =>
          log.debug("email cleaned: ", email.id)

        case email: EmailNotCleaned =>
          log.debug("email was not cleaned: ", email.id)
      }

    case message: HttpMessage =>
      val replyTo = sender()
      workers.tell(message, replyTo)

  }

  private def createWorkers(): ActorRef = {
    log.info("Creating workers")
    val numberOfWorkers = config.getInt(Configuration.numberOfWorkersPath)
    require(numberOfWorkers > 0, "At least one worker for cleaning data must be created!")

    val stopWords = config.getString(Configuration.stopWords)
    require(
      !stopWords.contains(",") || !stopWords.contains(" "),
      "stop-words setting is either one of 'default', 'google', 'mysql', or words separated by ','"
    )
    context.actorOf(
      ClusterRouterPool(
        ConsistentHashingPool(numberOfWorkers),
        ClusterRouterPoolSettings(totalInstances = numberOfWorkers * 10, maxInstancesPerNode = numberOfWorkers, allowLocalRoutees = true)
      ).props(
        CleanDataWorker
          .props(
            modelManager = modelManager,
            stemmer = createStemmer(config.getString(Configuration.stemmer)),
            takeFeatures = config.getInt(Configuration.takeFeatures),
            stopWords = config.getString(Configuration.stopWords)
          )
      ),
      name = CleanDataWorker.workerName
    )
  }

  private def createStemmer: String => Stemmer = {
    case "PORTER"    => new PorterStemmer
    case "LANCASTER" => new LancasterStemmer
    case other       => throw new IllegalStateException(s"$other is currently not supportet stemmer. Use 'LANCASTER' or 'PORTER' stemmer.")
  }
}
