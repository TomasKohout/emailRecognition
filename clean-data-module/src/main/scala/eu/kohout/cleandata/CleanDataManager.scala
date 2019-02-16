package eu.kohout.cleandata
import akka.actor.{Actor, ActorRef, Props}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager.{Configuration, EmailCleanded, EmailNotCleaned}
import eu.kohout.parser.Email
import smile.nlp.stemmer.{LancasterStemmer, PorterStemmer, Stemmer}

object CleanDataManager {
  val name: String = "CleanDataManager"

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
}

class CleanDataManager(
  config: Config,
  modelManager: ActorRef)
    extends Actor {

  private val log = Logger(getClass)

  private var workers: Router = _

  override def receive: Receive = {
    case email: Email =>
      log.debug("email received: ", email.id)
      workers.route(email, self)

    case email: EmailCleanded =>
      log.debug("email cleaned: ", email.id)

    case email: EmailNotCleaned =>
      log.debug("email was not cleaned: ", email.id)
  }

  private def createWorkers(): Unit = {
    val numberOfWorkers = config.getInt(Configuration.numberOfWorkersPath)
    require(numberOfWorkers > 0, "At least one worker for cleaning data must be created!")

    val stopWords = config.getString(Configuration.stopWords)
    require(
      !stopWords.contains(",") || !stopWords.contains(" "),
      "stop-words setting is either one of 'default', 'google', 'mysql', or words separated by ','"
    )
    var order = 0
    val routees = Vector.fill(numberOfWorkers) {

      val worker = context.actorOf(
        CleanDataWorker
          .props(
            modelManager = modelManager,
            stemmer = createStemmer(config.getString(Configuration.stemmer)),
            takeFeatures = config.getInt(Configuration.takeFeatures),
            stopWords = config.getString(Configuration.stopWords)
          ),
        CleanDataWorker.workerName + order
      )
      order += 1
      context watch worker
      ActorRefRoutee(worker)
    }

    workers = Router(RoundRobinRoutingLogic(), routees)
  }

  private def createStemmer: String => Stemmer = {
    case "PORTER"    => new PorterStemmer
    case "LANCASTER" => new LancasterStemmer
    case other       => throw new IllegalStateException(s"$other is currently not supportet stemmer. Use 'LANCASTER' or 'PORTER' stemmer.")
  }

  override def preStart(): Unit = {
    super.preStart()
    createWorkers()
  }
}
