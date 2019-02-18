package eu.kohout.cleandata
import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager.{EmailRecognitionRequest, HttpMessage, TrainData, TrainRequest}
import eu.kohout.model.manager.messages.ModelMessages.CleansedEmail
import eu.kohout.parser._
import smile.math.SparseArray
import smile.nlp._
import smile.nlp.stemmer.Stemmer

import scala.util.Try

object CleanDataWorker {
  val workerName = "CleanDataWorker"

  def props(
    modelManager: ActorRef,
    takeFeatures: Int,
    stopWords: String,
    stemmer: Stemmer
  ): Props = Props(new CleanDataWorker(modelManager, takeFeatures, Some(stopWords), Some(stemmer)))
}

class CleanDataWorker(
  modelManager: ActorRef,
  takeFeatures: Int,
  stopWords: Option[String],
  stemmer: Option[Stemmer])
    extends Actor {

  private val log = Logger(self.path.address.toString)

  override def receive: Receive = {
    case email: Email =>
      log.debug("Received email with id {}", email.id)
      modelManager ! cleanEmail(email)
    case httpMessage: HttpMessage =>
      httpMessage match {
        case message: EmailRecognitionRequest =>
          Try { EmailParser.parseFromString(message.text, EmailType.NotObtained) }
            .map { parsedEmail =>
            }

        case train: TrainRequest =>
          EmailParser.parseFromString()
      }
  }

  private def cleanEmail(email: Email): CleansedEmail = {
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

    log.debug("Email with id {} cleaned", email.id)

    CleansedEmail(
      id = email.id,
      data = cleanPlain(text),
      `type` = email.`type`,
      htmlTags = htmlTags
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
    val bags = concatenateSplitWords(text).bag("porter")
    val features = bags.toSeq.sortBy(_._2).toMap
    val count = if (features.size < takeFeatures) {
      features.size
    } else {
      takeFeatures
    }
    vectorize(features.keySet.take(count).toArray, bags)
  }

//  implicit def convertToDoubleArray: Array[Double] => Array[Double] = identity

  implicit def convertToSparseArray(array: Array[Double]): SparseArray =
    array
      .foldLeft(0, new SparseArray()) {
        case ((i, sparseArray), value) =>
          sparseArray.set(i, value)
          i + 1 -> sparseArray
      }
      ._2

  private def cleanHtml(body: String): (Map[String, Int], String) = removeHtml(body)
}
