package eu.kohout.cleandata
import SymSpell.SymSpell
import akka.Done
import akka.actor.{Actor, ActorRef, Props, Stash}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.sharding.ShardRegion
import akka.routing.{Broadcast, SmallestMailboxPool}
import com.thoughtworks.xstream.XStream
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager._
import eu.kohout.parser.{Email, EmailType}
import smile.feature.Bag

object CleanDataManager {
  val name: String = "CleanData"

  val idExtractor: ShardRegion.ExtractEntityId = {
    case msg => (name, msg)
  }

  val shardResolver: ShardRegion.ExtractShardId =
    _ => (math.abs(name.hashCode) % 100).toString

  def props(
    config: Config,
    modelManager: ActorRef
  ): Props = Props(new CleanDataManager(config = config, modelManager = modelManager))

  object Configuration {
    val configPath = "clean-data"
    val numberOfWorkersPath = "number-of-workers"
    val stemmer = "stemmer"
    val stopWords = "stop-words"
    val symspellDictionary = "symspell-dictionary"
    val concatenateChars = "concatenate-chars"
  }

  case class TrainData(email: Email) extends CleanDataManagerMessages
  case class PredictionData(email: Email) extends CleanDataManagerMessages

  case class CleanDataForDictionary(email: Email) extends CleanDataManagerMessages

  case class CleansedData(
    id: String,
    data: Seq[(String, Int)],
    `type`: EmailType)
      extends CleanDataManagerMessages

  case class ShareBag(bag: String) extends CleanDataManagerMessages

  case object GetBag extends CleanDataManagerMessages

  case class ShareSymspell(symSpell: SymSpell) extends CleanDataManagerMessages

  sealed trait CleanDataManagerMessages
}

class CleanDataManager(
  config: Config,
  modelManager: ActorRef)
    extends Actor
    with Stash {

  private val log = Logger(self.path.toStringWithoutAddress)

  private var bag: Bag[String] = _
  private val xStream = new XStream

  private val workers: ActorRef = createWorkers

  private def withDictionary: Receive = {
    case message: PredictionData =>
      workers.!(message)(sender())

    case message: TrainData =>
      workers.!(message)(sender())

    case msg: CleanDataForDictionary =>
      workers.!(msg)(sender())

    case GetBag =>
      sender() ! ShareBag(xStream.toXML(bag))

    case Done => throw new Exception ("Reseting actor")

    case other =>
      log.warn(s"Unexpected message recieved: $other ")

  }

  override def receive: Receive = {
    case _: TrainData =>
      stash()

    case _: PredictionData =>
      stash()

    case msg: CleanDataForDictionary =>
      log.debug("Forwarding clean data for dictionary with email id {}", msg.email.id)
      workers.!(msg)(sender())

    case msg: ShareBag =>
      bag = xStream.fromXML(msg.bag).asInstanceOf[Bag[String]]
      workers ! Broadcast(msg)
      context.become(withDictionary)

      unstashAll()

    case Done => throw new Exception ("Reseting actor")

    case other =>
      log.warn(s"Unexpected message recieved: $other ")

  }

  private def createWorkers: ActorRef = {
    val numberOfWorkers = config.getInt(Configuration.numberOfWorkersPath)
    require(numberOfWorkers > 0, "At least one worker for cleaning data must be created!")

    val stopWords = config.getString(Configuration.stopWords)
    require(
      !stopWords.contains(",") || !stopWords.contains(" "),
      "stop-words setting is either one of 'default', 'google', 'mysql', or words separated by ','"
    )
    val workers = context.actorOf(
      ClusterRouterPool(
        SmallestMailboxPool(numberOfWorkers),
        ClusterRouterPoolSettings(
          totalInstances = numberOfWorkers * 10,
          maxInstancesPerNode = numberOfWorkers,
          allowLocalRoutees = true
        )
      ).props(
          CleanDataWorker
            .props(
              modelManager = modelManager,
              stopWords = config.getString(Configuration.stopWords),
              config = config,
            )
            .withDispatcher("clean-dispatcher")
        )
        .withDispatcher("clean-dispatcher"),
      name = CleanDataWorker.workerName
    )

    workers
  }

}
