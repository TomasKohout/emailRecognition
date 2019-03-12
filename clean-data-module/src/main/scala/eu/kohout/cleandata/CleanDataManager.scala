package eu.kohout.cleandata
import SymSpell.SymSpell
import akka.actor.{Actor, ActorRef, Cancellable, Props, Stash}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.sharding.ShardRegion
import akka.routing.{Broadcast, SmallestMailboxPool}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.aggregator.ModelType
import eu.kohout.cleandata.CleanDataManager._
import eu.kohout.model.manager.ModelMessages.{CleansedEmail, TrainSeq}
import eu.kohout.parser.{Email, EmailType}
import smile.feature.Bag
import smile.nlp.stemmer.{LancasterStemmer, PorterStemmer, Stemmer}

import scala.concurrent.duration._

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

  case class TrainData(
    emails: Seq[Email],
    models: Seq[ModelType] = Seq.empty)
      extends CleanDataManagerMessages

  case class CleanDataForDictionary(
    emails: Seq[Email],
    replyTo: ActorRef)
      extends CleanDataManagerMessages

  case class CleanData(email: Email, replyTo: Option[ActorRef] = None) extends CleanDataManagerMessages

  case class CleansedData(
    id: String,
    data: Seq[(String, Int)],
    `type`: EmailType,
    htlmTags: Map[String, Int])
      extends CleanDataManagerMessages

  case class DecreaseSizeBecauseOfError(throwable: Throwable) extends CleanDataManagerMessages

  case class Dictionary(data: Seq[CleansedData]) extends CleanDataManagerMessages

  case class TestData(email: Email, replyTo: Option[ActorRef] = None) extends CleanDataManagerMessages

  case class EmailNotCleaned(id: String) extends CleanDataManagerMessages

  case class EmailCleaned(id: String) extends CleanDataManagerMessages

  case class ShareBag(bag: Bag[String]) extends CleanDataManagerMessages

  case object GetBag extends CleanDataManagerMessages

  case object ContinueProcess extends CleanDataManagerMessages

  case class ShareSymspell(symSpell: SymSpell) extends CleanDataManagerMessages

  sealed trait CleanDataManagerMessages
}

class CleanDataManager(
  config: Config,
  modelManager: ActorRef)
    extends Actor
    with Stash {

  private val log = Logger(self.path.toStringWithoutAddress)

  private var dictionaryResolver: ActorRef = _
  private var bag: Bag[String] = _
  private var sizeOfTrainData = 0
  private var cleansedDatas: Seq[CleansedData] = Seq.empty
  private var trainDatas: Seq[CleansedEmail] = Seq.empty

  private var cancelable: Option[Cancellable] = None

  private val symspell = {
    val symSpell = new SymSpell(-1, 3, -1, 1)
    symSpell.loadDictionary(config.getString(Configuration.symspellDictionary), 0, 1)
    symSpell
  }

  private val workers: ActorRef = createWorkers(symspell)

  private def withDictionary: Receive = {
    case msg: TestData =>
      workers ! msg

    case GetBag =>
      sender() ! ShareBag(bag)

    case error: DecreaseSizeBecauseOfError =>
      sizeOfTrainData = sizeOfTrainData - 1
      log.error("Error when cleaning data {}, {}", error.throwable.getMessage, error.throwable.getStackTrace.map(_.toString).mkString("\n"))

      if (sizeOfTrainData == 0) {
        modelManager ! TrainSeq(trainDatas)
        trainDatas = Seq.empty
      }
      ()
    case cleansedData: CleansedData =>
      log.debug("Cleansed data received {}", cleansedData.id)
      cleansedDatas = cleansedData +: cleansedDatas
      sizeOfTrainData = sizeOfTrainData - 1
      log.debug("CleansedData size of sizeOfTrainData {}", sizeOfTrainData)

      if (sizeOfTrainData == 0) {

        cleansedDatas
          .foreach(workers !)

        sizeOfTrainData = cleansedDatas.size
        cleansedDatas = Seq.empty
      }
      ()

    case trainData: CleansedEmail =>
      log.debug("TrainData received {}", trainData.id)
      trainDatas = trainData +: trainDatas
      sizeOfTrainData = sizeOfTrainData - 1

      log.debug("Train sizeOfTrainData {}", sizeOfTrainData)
      if (sizeOfTrainData == 0) {
        modelManager ! TrainSeq(trainDatas)
        trainDatas = Seq.empty
      }

    case message: TrainData =>
      log.info("Train email received. size {}", message.emails.size)

      sizeOfTrainData = message.emails.size

      message.emails
        .foreach { email =>
          workers ! CleanData(email)
        }

    case msg: CleanData =>
      val replyTo = sender()
      workers ! TestData(msg.email, Some(replyTo))

    case other =>
      log.warn(s"Unexpected message recieved: $other ")

  }

  override def receive: Receive = {
    case _: TrainData =>
      stash()

    case _: TestData =>
      stash()

    case ContinueProcess =>
      if (sizeOfTrainData != 0) {
        dictionaryResolver ! Dictionary(cleansedDatas)
        cleansedDatas = Seq.empty
      }

    case CleanDataForDictionary(data, replyTo) =>
      dictionaryResolver = replyTo
      sizeOfTrainData = data.size

      data
        .foreach { email =>
          workers ! CleanData(email)
        }

    case error: DecreaseSizeBecauseOfError =>
      sizeOfTrainData = sizeOfTrainData - 1
      log.error(
        "Error when cleaning data {}, stack: {}",
        error.throwable.getMessage,
        error.throwable.getStackTrace.map(_.toString).mkString("\n")
      )

      cancelable.map(_.cancel())
      cancelable = Some(context.system.scheduler.scheduleOnce(1 minute, self, ContinueProcess)(context.dispatcher))

      if (sizeOfTrainData == 0) {
        dictionaryResolver ! Dictionary(cleansedDatas)
        cleansedDatas = Seq.empty
      }

    case cleansedData: CleansedData =>
      log.debug("Cleansed data received {}", cleansedData.id)
      cleansedDatas = cleansedData +: cleansedDatas
      sizeOfTrainData = sizeOfTrainData - 1
      log.debug("CleansedData size of sizeOfTrainData {}", sizeOfTrainData)

      cancelable.map(_.cancel())
      cancelable = Some(context.system.scheduler.scheduleOnce(1 minute, self, ContinueProcess)(context.dispatcher))

      if (sizeOfTrainData == 0) {
        dictionaryResolver ! Dictionary(cleansedDatas)
        cleansedDatas = Seq.empty
      }

    case msg: ShareBag =>
      bag = msg.bag
      workers ! Broadcast(msg)
      context.become(withDictionary)

      unstashAll()

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
        ClusterRouterPoolSettings(totalInstances = numberOfWorkers * 10, maxInstancesPerNode = numberOfWorkers, allowLocalRoutees = true)
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
    case other       => throw new IllegalStateException(s"$other is currently not supportet stemmer. Use 'LANCASTER' or 'PORTER' stemmer.")
  }

}
