package eu.kohout.cleandata

import java.io.{File, FileInputStream}

import SymSpell.SymSpell
import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import com.thoughtworks.xstream.XStream
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager._
import eu.kohout.model.manager.ModelMessages
import eu.kohout.parser.BodyType.{HTML, PLAIN}
import eu.kohout.parser.Email
import org.jsoup.Jsoup
import smile.feature.Bag
import smile.nlp.stemmer.{LancasterStemmer, PorterStemmer, Stemmer}
import smile.nlp._

import scala.concurrent.duration._
import scala.util.Try
import collection.JavaConverters._

object CleanDataWorker {
  val workerName = "CleanDataWorker"

  def props(
    modelManager: ActorRef,
    stopWords: String,
    config: Config
  ): Props =
    Props(new CleanDataWorker(modelManager, stopWords, config))

}

class CleanDataWorker(
  modelManager: ActorRef,
  stopWords: String,
  config: Config)
    extends Actor{
  private val log = Logger(self.path.toStringWithoutAddress)

  private def createStemmer: String => Stemmer = {
    case "PORTER"    => new PorterStemmer
    case "LANCASTER" => new LancasterStemmer
    case other =>
      throw new IllegalStateException(
        s"$other is currently not supportet stemmer. Use 'LANCASTER' or 'PORTER' stemmer."
      )
  }

  private val stemmer = createStemmer(config.getString(Configuration.stemmer))

  private val symspell = {
    val symSpell = new SymSpell(500, 2, -1, 1)
    val file = new File(config.getString(Configuration.symspellDictionary))
    if(file.isFile)
      symSpell.loadDictionary(new FileInputStream(file) , 0, 1)
    else
      symSpell.loadDictionary(getClass.getResourceAsStream(config.getString(Configuration.symspellDictionary)) , 0, 1)
    symSpell
  }

  private val xstream = new XStream

  private implicit val concatenateChars: Int = config.getInt(Configuration.concatenateChars)

  implicit val timeout: Timeout = 5 seconds

  private var bag: Option[Bag[String]] = None

  override def receive: Receive = withoutBag

  private def withoutBag: Receive = {
    case msg: CleanDataForDictionary =>
      val replyTo = sender()
      log.debug("Cleaning data for dictionary with email id {}", msg.email.id)
      cleanEmail(msg.email)
        .map(replyTo !)
        .recover {
          case ex =>
            log.error(s"Exception occured when cleaning email with id ${msg.email.id}", ex)
        }
        .getOrElse(())

    case shareBag: ShareBag =>
      bag = Some(xstream.fromXML(shareBag.bag).asInstanceOf[Bag[String]])
      log.info("Becoming withBagOfWords")
      context.become(withBagOfWords)

    case other =>
      log.warn("Received message that should not be here {}", other)
  }

  private def withBagOfWords: Receive = {
    case message: PredictionData =>
      log.debug("Prediction data received with id {}", message.email.id)
      receiveMessage(message.email, ModelMessages.Predict, sender())

    case message: TrainData =>
      log.debug("Train data received with id {}", message.email.id)
      receiveMessage(message.email, ModelMessages.Train, sender())

    case other =>
      log.warn("Received message that should not be here {}", other)
  }

  private def receiveMessage(
    email: Email,
    responseCreator: ModelMessages.CleansedEmail => ModelMessages.ModelMessages,
    replyTo: ActorRef
  ): Unit =
    cleanEmail(email)
      .map { cleanEmail =>
        bag
          .map(_.feature(cleanEmail.data.map(_._1).toArray))
          .map(
            features =>
              ModelMessages
                .CleansedEmail(
                  id = cleanEmail.id,
                  data = features,
                  `type` = cleanEmail.`type`
                )
          )
          .fold {
            context.parent ! GetBag
            context.become(withoutBag)
          }(data => modelManager.!(responseCreator(data))(replyTo))
      }
      .recover {
        case ex =>
          log.error("Error occurred when cleaning data.", ex)
      }
      .getOrElse(())

  private def cleanEmail(email: Email): Try[CleanDataManager.CleansedData] = Try {
    val text = email.bodyParts
      .map { bodyPart =>
        bodyPart.`type` match {
          case HTML  => cleanHtml(bodyPart.body)
          case PLAIN => bodyPart.body
        }
      }
      .reduceLeft(_ + " " + _)

    val cleanedText = concatenateSplitWords(text)
      .words(stopWords)
      .flatMap(symspell.wordSegmentation(_).correctedString.words(stopWords))
      .flatMap(
        word =>
          try {
            Some(stemmer.stem(word))
          } catch {
            case _: java.lang.ArrayIndexOutOfBoundsException => None
          }
      )
      .groupBy(identity)
      .map { case (word, groupedWords) => word -> groupedWords.length }(
        collection.breakOut[Map[String, Array[String]], (String, Int), Seq[(String, Int)]]
      )

    log.debug("Email with id {} cleaned", email.id)

    CleansedData(
      id = email.id,
      data = cleanedText,
      `type` = email.`type`
    )
  }

  private def concatenateSplitWords(str: String)(implicit concatenateChars: Int): String = {
    val (result, currResult) = str.split(' ').foldLeft("", "") {
      case ((res, curResult), s) =>
        if (s.length <= concatenateChars) {
          (res, curResult + s)
        } else {
          (res + " " + curResult + " " + s, "")
        }
    }
    result + " " + currResult
  }

  private def cleanHtml(body: String): String =
    Jsoup.parse(body).getAllElements.asScala.map(_.text).mkString(" ")

  override def preRestart(
    reason: Throwable,
    message: Option[Any]
  ): Unit = {
    super.preRestart(reason, message)
    bag.map(xstream.toXML).map(ShareBag).foreach(self !)
    log.warn("Reason why I am restarted {}, stack {}", reason, reason.getStackTrace)
  }
}
