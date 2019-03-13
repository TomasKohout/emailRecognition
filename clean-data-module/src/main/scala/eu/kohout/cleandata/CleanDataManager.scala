package eu.kohout.cleandata
import SymSpell.SymSpell
import akka.Done
import akka.actor.{Actor, ActorRef, Props, Stash}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.sharding.ShardRegion
import akka.routing.{Broadcast, SmallestMailboxPool}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager._
import eu.kohout.parser.{Email, EmailType}
import smile.feature.Bag
import smile.nlp.stemmer.{LancasterStemmer, PorterStemmer, Stemmer}

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

  }

  case class TrainData(email: Email) extends CleanDataManagerMessages
  case class PredictionData(email: Email) extends CleanDataManagerMessages

  case class CleanDataForDictionary(email: Email) extends CleanDataManagerMessages

  case class CleansedData(
    id: String,
    data: Seq[(String, Int)],
    `type`: EmailType,
    htlmTags: Map[String, Int])
      extends CleanDataManagerMessages

  case class ShareBag(bag: Bag[String]) extends CleanDataManagerMessages

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

  private val symspell = {
    val symSpell = new SymSpell(-1, 3, -1, 1)
    symSpell.loadDictionary(config.getString(Configuration.symspellDictionary), 0, 1)
    symSpell
  }

  private val workers: ActorRef = createWorkers(symspell)

  private def withDictionary: Receive = {
    case message: PredictionData =>
      workers.!(message)(sender())

    case message: TrainData =>
      workers.!(message)(sender())

    case msg: CleanDataForDictionary =>
      workers.!(msg)(sender())

    case GetBag =>
      sender() ! ShareBag(bag)

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
      bag = msg.bag
      workers ! Broadcast(msg)
      context.become(withDictionary)

      unstashAll()

    case Done => throw new Exception ("Reseting actor")

    case other =>
      log.warn(s"Unexpected message recieved: $other ")

  }

  private def createWorkers(symspell: SymSpell): ActorRef = {
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
              symspell = symspell,
              modelManager = modelManager,
              stopWords = config.getString(Configuration.stopWords),
              config = config,
              stemmer = _ => createStemmer(config.getString(Configuration.stemmer))
            )
            .withDispatcher("clean-dispatcher")
        )
        .withDispatcher("clean-dispatcher"),
      name = CleanDataWorker.workerName
    )

    workers
  }

  private def createStemmer: String => Stemmer = {
    case "PORTER"    => new PorterStemmer
    case "LANCASTER" => new LancasterStemmer
    case other =>
      throw new IllegalStateException(
        s"$other is currently not supportet stemmer. Use 'LANCASTER' or 'PORTER' stemmer."
      )
  }

}
