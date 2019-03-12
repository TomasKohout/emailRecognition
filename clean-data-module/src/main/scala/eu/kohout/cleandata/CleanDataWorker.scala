package eu.kohout.cleandata
import java.util.regex.Pattern

import SymSpell.SymSpell
import akka.actor.{Actor, ActorRef, Props, Stash}
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager._
import eu.kohout.model.manager.ModelMessages.{CleansedEmail, ModelMessages, Predict}
import eu.kohout.parser.BodyType.{HTML, PLAIN}
import eu.kohout.parser.Email
import smile.feature.Bag
import smile.nlp.stemmer.{PorterStemmer, Stemmer}
import smile.nlp._

import scala.concurrent.duration._
import scala.util.{Success, Try}
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
  ): Props = Props(new CleanDataWorker(symspell, modelManager, Some(stopWords), stemmer(()), config))

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

  private val urlPatternStr = "((https?|ftp|gopher|telnet|file|Unsure|http):((//)|(\\\\))+[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]*)"
  private val linuxPathPatternStr = "^(?!-)[a-z0-9-/]+(?<!-)(/(?!-)[a-z0-9-]+(?<!-))*(/(?!-\\.)[a-z0-9-\\.]+(?<!-\\.))?$"
  private val absoluteWindowsPatternStr = "[A-Za-z]:[A-Za-z0-9\\!\\@\\#\\$\\%\\^\\&\\(\\)\\'\\;\\{\\}\\[\\]\\=\\+\\-\\_\\~\\`\\.\\\\]+"

  private val urlPattern = Pattern.compile(urlPatternStr, Pattern.CASE_INSENSITIVE)
  private val relativePathPattern = Pattern.compile(linuxPathPatternStr, Pattern.CASE_INSENSITIVE)
  private val absoluteWindowsPattern = Pattern.compile(absoluteWindowsPatternStr, Pattern.CASE_INSENSITIVE)

  override def receive: Receive = withoutBag

  private def withoutBag: Receive = {

    case message: CleanData =>
      log.debug("Train data recieved with id {}", message.email.id)

      sender() ! cleanEmail(message.email)
        .transform(
          Success(_),
          ec => Success(DecreaseSizeBecauseOfError(ec))
        )
        .get

    case _: TestData =>
      stash()

    case _: CleansedData =>
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

    case message: CleanData =>
      log.debug("Train data recieved with id {}", message.email.id)

      sender() ! cleanEmail(message.email).fold(DecreaseSizeBecauseOfError(_), identity)

    case message: CleansedData =>
      log.debug("Cleansed data received with id {}", message.id)
      sender() ! bag
        .map(_.feature(message.data.map(_._1).toArray))
        .map(
          result =>
            CleansedEmail(
              id = message.id,
              data = result,
              `type` = message.`type`,
              htmlTags = message.htlmTags
            )
        )
        .getOrElse {
          context.parent ! GetBag
          DecreaseSizeBecauseOfError(None.orNull)
        }

    case message: TestData =>
      log.debug("Test data recieved with id {}", message.email.id)
      cleanEmail(message.email)
        .map(
          cleansedData =>
            bag
              .map(_.feature(cleansedData.data.map(_._1).toArray))
              .fold(context.parent ! GetBag)(
                result =>
                  modelManager ! Predict(
                    data = CleansedEmail(
                      id = message.email.id,
                      data = result,
                      `type` = message.email.`type`,
                      htmlTags = cleansedData.htlmTags
                    )
                  )
              )
        )
      ()

    case DecreaseSizeBecauseOfError => context.parent ! DecreaseSizeBecauseOfError
    case other =>
      log.warn("Received message that should not be here {}", other)
  }

  private def removeRelativePath(str: String): String = {
    var tmp = str
    val m = relativePathPattern.matcher(tmp)
    var i = 0
    while (m.find) {
      tmp = tmp.replaceAll(m.group(i), "").trim
      i += 1
    }
    tmp
  }

  private def removeUrl(str: String): String = {
    var tmp = str
    val m = urlPattern.matcher(tmp)
    var i = 0
    while (m.find) {
      tmp = tmp.replaceAll(m.group(i), "").trim
      i += 1
    }
    tmp
  }

  private def removeWindowsAbsolutePath(str: String): String = {
    var tmp = str
    val m = absoluteWindowsPattern.matcher(tmp)
    var i = 0
    while (m.find) {
      tmp = tmp.replaceAll(m.group(i), "").trim
      i += 1
    }
    tmp
  }

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
      .flatMap { sentece =>
//        log.debug("$BeforeSymspell id: {} : {}", email.id, sentece)
        val res = symspell.lookupCompound(sentece).asScala.map(_.term)
//        log.debug("$AfterSymspell id: {} : {}", email.id, res.mkString(" "))
        res
      }
      .flatMap(_.words())
      .map(
        _.flatMap(
          char =>
            if (Character.isLetter(char) || char == ' ') {
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

//  private def cleanPlain(text: String): Array[Double] = {
//    //TODO change porter with configuration
//    val bags = concatenateSplitWords(text).bag()
//    val features = bags.toSeq.sortWith(_._2 > _._2)
//    val count = if (features.size < takeFeatures) {
//      features.size
//    } else {
//      takeFeatures
//    }
//    val result = vectorize(
//      features
//        .map(_._1)(collection.breakOut[Seq[(String, Int)], String, Set[String]])
//        .take(count)
//        .toArray,
//      bags
//    )
//
//    if (result.length < takeFeatures) {
//      val different = takeFeatures - result.length
//      val differentFill = Vector.fill(different) {
//        0.0
//      }
//
//      result ++ differentFill
//    } else {
//      result
//    }
//  }

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
    context.parent ! DecreaseSizeBecauseOfError(reason)
  }
}
