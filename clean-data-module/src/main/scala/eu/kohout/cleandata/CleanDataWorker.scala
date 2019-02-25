package eu.kohout.cleandata
import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager._
import eu.kohout.cleandata.CleanDataWorker.ProcessStashedData
import eu.kohout.model.manager.messages.ModelMessages.{CleansedEmail, Predict, Train}
import eu.kohout.parser._
import org.apache.commons.lang3.CharUtils
import smile.feature.Bag
import smile.nlp._
import smile.nlp.stemmer.{PorterStemmer, Stemmer}

import scala.concurrent.duration._
import scala.util.{Success, Try}

object CleanDataWorker {
  val workerName = "CleanDataWorker"

  def props(
    modelManager: ActorRef,
    takeFeatures: Int,
    stopWords: String,
    stemmer: Stemmer
  ): Props = Props(new CleanDataWorker(modelManager, takeFeatures, Some(stopWords), stemmer))

  private case object ProcessStashedData
}

class CleanDataWorker(
  modelManager: ActorRef,
  takeFeatures: Int,
  stopWords: Option[String],
  stemmer: Stemmer = new PorterStemmer)
    extends Actor {

  implicit val timout: Timeout = 5 seconds

  private val log = Logger(self.path.toStringWithoutAddress)
  private var bag: Bag[String] = _
  private var stashedMessages: List[TestData] = List.empty
  override def receive: Receive = {

    case message: CleanData =>
      log.debug("Train data recieved with id {}", message.email.id)

      sender() ! Try(cleanEmail(message.email))
        .transform(
          Success(_),
          ec => Success(DecreaseSizeBecauseOfError(ec))
        )
        .get

    case message: TestData =>
      stashedMessages = message :: stashedMessages
      ()
    case shareBag: ShareBag =>
      bag = shareBag.bag
      log.info("Becoming withBagOfWords")
      context.become(withBagOfWords)
      self ! ProcessStashedData

    case other =>
      log.warn("Received message that should not be here {}", other)
  }

  private def withBagOfWords: Receive = {

    case message: CleanData =>
      log.debug("Train data recieved with id {}", message.email.id)
      sender() ! cleanEmail(message.email)

    case message: CleansedData =>
      log.debug("Cleansed data received with id {}", message.id)
      val train = bag.feature(message.data.map(_._1).toArray)

      sender() ! Train(
        CleansedEmail(
          id = message.id,
          data = train,
          `type` = message.`type`,
          htmlTags = message.htlmTags
        )
      )

    case message: TestData =>
      log.debug("Test data recieved with id {}", message.email.id)
      val cleansedData = cleanEmail(message.email)

      modelManager ! Predict(
        data = CleansedEmail(
          id = message.email.id,
          data = bag.feature(cleansedData.data.map(_._1).toArray),
          `type` = message.email.`type`,
          htmlTags = cleansedData.htlmTags
        )
      )

    case ProcessStashedData =>
      stashedMessages
        .foreach(self !)

      stashedMessages = List.empty

    case other =>
      log.warn("Received message that should not be here {}", other)
  }

  private def cleanEmail(email: Email): CleansedData = {
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

    val cleanedText = concatenateSplitWords(text)
      .flatMap(
        char =>
          if (Character.isLetter(char) || char == ' ') {
            Some(char.toLower)
          } else {
            None
          }
      )
      .sentences
      .flatMap(_.words())
      .map(stemmer.stem)
      .groupBy(identity)
      .map(data => data._1 -> data._2.length)(collection.breakOut[Map[String, Array[String]], (String, Int), Seq[(String, Int)]])

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
    val (tags, _, message, _) = str.foldLeft(List.empty[String], "", "", false) {
      case ((listOfTags, tag, text, isInTag), char) =>
        if (char == '<') {
          (listOfTags, tag, text, true)
        } else if (char == '>') {
          (tag :: listOfTags, "", text + " ", false)
        } else if (isInTag) {
          (listOfTags, tag + char, text, isInTag)
        } else {
          (listOfTags, tag, text + char, isInTag)
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

  private def cleanPlain(text: String): Array[Double] = {
    //TODO change porter with configuration
    val bags = concatenateSplitWords(text).bag()
    val features = bags.toSeq.sortWith(_._2 > _._2)
    val count = if (features.size < takeFeatures) {
      features.size
    } else {
      takeFeatures
    }
    val result = vectorize(
      features
        .map(_._1)(collection.breakOut[Seq[(String, Int)], String, Set[String]])
        .take(count)
        .toArray,
      bags
    )

    if (result.length < takeFeatures) {
      val different = takeFeatures - result.length
      val differentFill = Vector.fill(different) {
        0.0
      }

      result ++ differentFill
    } else {
      result
    }
  }

  private def cleanHtml(body: String): (Map[String, Int], String) = removeHtml(body)
}
