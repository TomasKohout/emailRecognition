package eu.kohout.cleandata

import SymSpell.SymSpell
import akka.actor.{Actor, ActorRef, Props, Stash}
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager._
import eu.kohout.model.manager.ModelMessages
import eu.kohout.parser.BodyType.{HTML, PLAIN}
import eu.kohout.parser.Email
import smile.feature.Bag
import smile.nlp.stemmer.{PorterStemmer, Stemmer}
import smile.nlp._

import scala.concurrent.duration._
import scala.util.Try
import collection.JavaConverters._

object CleanDataWorker {
  val workerName = "CleanDataWorker"

  object Configuration {}

  def props(
    symspell: SymSpell,
    modelManager: ActorRef,
    stopWords: String,
    stemmer: Unit => Stemmer = _ => new PorterStemmer,
    config: Config
  ): Props =
    Props(new CleanDataWorker(symspell, modelManager, Some(stopWords), stemmer(()), config))

}

class CleanDataWorker(
  symspell: SymSpell,
  modelManager: ActorRef,
  stopWords: Option[String],
  stemmer: Stemmer,
  config: Config)
    extends Actor
    with Stash {

  implicit val timout: Timeout = 5 seconds

  private val log = Logger(self.path.toStringWithoutAddress)
  private var bag: Option[Bag[String]] = None

  override def receive: Receive = withoutBag

  private def withoutBag: Receive = {
    case msg: CleanDataForDictionary =>
      log.debug("Cleaning data for dictionary with email id {}", msg.email.id)
      cleanEmail(msg.email)
        .map(sender() !)
        .recover {
          case ex =>
            log.error(s"Exception occured when cleaning email with id ${msg.email.id}", ex)
        }
        .getOrElse(())

    case _: PredictionData =>
      stash()

    case _: TrainData =>
      stash()

    case shareBag: ShareBag =>
      bag = Some(shareBag.bag)
      log.info("Becoming withBagOfWords")
      context.become(withBagOfWords)
      unstashAll()

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
                  `type` = cleanEmail.`type`,
                  htmlTags = cleanEmail.htlmTags
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
      }.getOrElse(())

  private def cleanEmail(email: Email): Try[CleanDataManager.CleansedData] = Try {
    val (htmlTags, text) = email.bodyParts
      .map { bodyPart =>
        bodyPart.`type` match {
          case HTML  => cleanHtml(bodyPart.body)
          case PLAIN => Map.empty -> bodyPart.body
        }
      }
      .foldLeft(Map.empty[String, Int], "") {
        case ((resultMap, resultText), (map, text)) =>
          (resultMap ++ map, resultText + text)
      }

    val cleanedText = concatenateSplitWords(text).sentences
      .flatMap(symspell.lookupCompound(_).asScala.map(_.term))
      .flatMap(_.words())
      .map(
        _.flatMap(
          char =>
            if (Character.isLetter(char) || Character.isWhitespace(char)) {
              Some(char.toLower)
            } else {
              None
            }
        )
      )
      .flatMap(
        word =>
          try {
            Some(stemmer.stem(word))
          } catch {
            case ex: java.lang.ArrayIndexOutOfBoundsException => None
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
      `type` = email.`type`,
      htlmTags = htmlTags
    )
  }

  private def concatenateSplitWords(str: String): String = {
    val (result, currResult) = str.split(' ').foldLeft("", "") {
      case ((res, curResult), s) =>
        if (s.length == 1) {
          (res, curResult + s)
        } else {
          (res + " " + curResult + " " + s, "")
        }
    }
    result + " " + currResult
  }

  private def removeHtml(str: String): (Map[String, Int], String) = {
    val (tags, _, message, _, _, _) =
      str.foldLeft(List.empty[String], "", "", false, false, false) {
        case ((listOfTags, tag, text, isInTag, isInSpecial, isHead), char) =>
          if (isHead) {
            if (tag.toLowerCase.contains("/head")) {
              ("head" :: listOfTags, "", text, false, isInSpecial, false)
            } else {
              (listOfTags, tag + char, text, isInTag, isInSpecial, isHead)
            }
          } else if (char == '<') {
            (listOfTags, tag, text, true, false, false)
          } else if (char == '>') {
            (
              tag :: listOfTags,
              "",
              text + " ",
              false,
              false,
              tag.toLowerCase.contains("head")
            )
          } else if (isInTag) {
            (listOfTags, tag + char, text, isInTag, isInSpecial, isHead)
          } else if (char == '&') {
            (listOfTags, "", text + " ", false, true, false)
          } else if (char == ';') {
            (listOfTags, "", text + " ", false, false, false)
          } else if (isInSpecial) {
            (listOfTags, tag + char, text, isInTag, isInSpecial, isHead)
          } else {
            (listOfTags, tag, text + char, isInTag, isInSpecial, isHead)
          }
      }

    val resultMap = tags
      .map { str =>
        if (str.contains(" ")) {
          str.substring(0, str.indexOf(" ")).replace("/", "").toLowerCase()
        } else {
          str.replace("/", "").toLowerCase()
        }
      }
      .groupBy(identity)
      .map {
        case (key, listOfAll) =>
          (key, listOfAll.size)
      }(collection.breakOut[Map[String, List[String]], (String, Int), Map[String, Int]])

    (resultMap, message)
  }

  private def cleanHtml(body: String): (Map[String, Int], String) = removeHtml(body)

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    context.parent ! GetBag
  }

  override def preRestart(
    reason: Throwable,
    message: Option[Any]
  ): Unit = {
    super.preRestart(reason, message)
    log.info("Reason why i am restarted {}, stack {}", reason, reason.getStackTrace)
  }
}
