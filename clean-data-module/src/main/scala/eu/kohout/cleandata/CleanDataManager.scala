package eu.kohout.cleandata
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.singleton.{
  ClusterSingletonManager,
  ClusterSingletonManagerSettings,
  ClusterSingletonProxy,
  ClusterSingletonProxySettings
}

import akka.routing._
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager._
import eu.kohout.model.manager.messages.ModelMessages.{CleansedEmail, FeatureSizeForBayes, Train, TrainSeq}
import eu.kohout.parser.EmailType.{Ham, Spam}
import eu.kohout.parser.{Email, EmailType}
import eu.kohout.types.ModelTypes

import smile.feature.Bag
import smile.nlp.stemmer.{LancasterStemmer, PorterStemmer, Stemmer}

object CleanDataManager {
  val name: String = "CleanData"

  def asClusterSingleton(
    props: Props,
    appCfg: Config,
    system: ActorSystem
  ): ActorRef = {

    val singleton = system
      .actorOf(
        ClusterSingletonManager
          .props(
            singletonProps = props.withDispatcher("clean-dispatcher"),
            terminationMessage = PoisonPill,
            settings = ClusterSingletonManagerSettings(system)
          )
          .withDispatcher("clean-dispatcher"),
        name = name + "Manager"
      )

    system.actorOf(
      ClusterSingletonProxy
        .props(
          singletonManagerPath = singleton.path.toStringWithoutAddress,
          settings = ClusterSingletonProxySettings(system)
        )
        .withDispatcher("clean-dispatcher"),
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

  case class TrainData(
    emails: Seq[Email],
    models: Seq[ModelTypes] = Seq.empty)
      extends CleanDataManagerMessages

  case class CleanData(email: Email) extends CleanDataManagerMessages

  case class CleansedData(
    id: String,
    data: Seq[(String, Int)],
    `type`: EmailType,
    htlmTags: Map[String, Int])
      extends CleanDataManagerMessages

  case class DecreaseSizeBecauseOfError(throwable: Throwable) extends CleanDataManagerMessages

  case class TestData(email: Email) extends CleanDataManagerMessages

  case class EmailNotCleaned(id: String) extends CleanDataManagerMessages

  case class EmailCleaned(id: String) extends CleanDataManagerMessages

  case class ShareBag(bag: Bag[String]) extends CleanDataManagerMessages

  sealed trait CleanDataManagerMessages
}

class CleanDataManager(
  config: Config,
  modelManager: ActorRef)
    extends Actor {

  private val log = Logger(self.path.toStringWithoutAddress)
  private val takeFeatures = config.getInt(Configuration.takeFeatures)

  private var bag: Bag[String] = _
  private var sizeOfTrainData = 0
  private var cleansedDatas: Seq[CleansedData] = Seq.empty
  private var trainDatas: Seq[CleansedEmail] = Seq.empty

  private val workers: ActorRef = createWorkers()

  override def receive: Receive = {

    case error: DecreaseSizeBecauseOfError =>
      sizeOfTrainData = sizeOfTrainData - 1
      log.error("Error when cleaning data {}", error.throwable.getMessage)

      if (sizeOfTrainData == 0) {
        modelManager ! TrainSeq(trainDatas)
      }
      ()
    case cleansedData: CleansedData =>
      log.debug("Cleansed data received {}", cleansedData.id)
      cleansedDatas = cleansedData +: cleansedDatas
      sizeOfTrainData = sizeOfTrainData - 1
      log.debug("CleansedData size of sizeOfTrainData {}", sizeOfTrainData)
      if (sizeOfTrainData == 0) {
        val groupedByType = cleansedDatas.groupBy(_.`type`)

        val hamFeatures = groupedByType
          .getOrElse(Ham, Seq.empty)
          .flatMap(_.data)
          .sortWith(_._2 > _._2)
          .take(takeFeatures)
          .map(_._1)

        val spamFeatures = groupedByType
          .getOrElse(Spam, Seq.empty)
          .flatMap(_.data)
          .sortWith(_._2 > _._2)
          .take(takeFeatures)
          .map(_._1)

        val features = hamFeatures
          .intersect(spamFeatures)
          .foldLeft((hamFeatures ++ spamFeatures).toSet)((featuresSet, string) => featuresSet - string)

        bag = new Bag(features.toArray)

        modelManager ! FeatureSizeForBayes(features.size)

        workers ! Broadcast(ShareBag(bag))

        cleansedDatas
          .foreach(workers !)

        sizeOfTrainData = cleansedDatas.size
        cleansedDatas = Seq.empty
      }
      ()

    case trainData: Train =>
      log.debug("TrainData received {}", trainData.data.id)
      trainDatas = trainData.data +: trainDatas
      sizeOfTrainData = sizeOfTrainData - 1

      log.debug("Train sizeOfTrainData {}", sizeOfTrainData)
      if (sizeOfTrainData == 0) {
        modelManager ! TrainSeq(trainDatas)
      }

    case message: TrainData =>
      log.info("Train email received. size {}", message.emails.size)

      sizeOfTrainData = message.emails.size

      message.emails
        .foreach { email =>
          workers ! CleanData(email)
        }

    case message: TestData =>
      log.info("Test email received: ", message.email.id)
      workers ! message

    case email: EmailCleaned =>
      log.debug("email cleaned: ", email.id)

    case email: EmailNotCleaned =>
      log.debug("email was not cleaned: ", email.id)

    case other =>
      log.warn(s"Unexpected message recieved: $other ")

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
        RoundRobinPool(numberOfWorkers),
        ClusterRouterPoolSettings(totalInstances = numberOfWorkers * 10, maxInstancesPerNode = numberOfWorkers, allowLocalRoutees = true)
      ).props(
          CleanDataWorker
            .props(
              modelManager = modelManager,
              stemmer = createStemmer(config.getString(Configuration.stemmer)),
              takeFeatures = config.getInt(Configuration.takeFeatures),
              stopWords = config.getString(Configuration.stopWords)
            )
            .withDispatcher("clean-dispatcher")
        )
        .withDispatcher("clean-dispatcher"),
      name = CleanDataWorker.workerName
    )
  }

  private def createStemmer: String => Stemmer = {
    case "PORTER"    => new PorterStemmer
    case "LANCASTER" => new LancasterStemmer
    case other       => throw new IllegalStateException(s"$other is currently not supportet stemmer. Use 'LANCASTER' or 'PORTER' stemmer.")
  }
}
