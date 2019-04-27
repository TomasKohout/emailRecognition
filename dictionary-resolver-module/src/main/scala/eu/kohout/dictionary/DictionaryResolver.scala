package eu.kohout.dictionary

import java.io.{File, FileWriter, InputStream}
import java.time.Instant
import java.util.Scanner

import akka.Done
import akka.actor.{Actor, ActorRef, Props}
import com.thoughtworks.xstream.XStream
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager.CleansedData
import eu.kohout.dictionary.DictionaryResolver.{Configuration, Dictionary, ResolveDictionary}
import eu.kohout.parser.EmailType
import eu.kohout.parser.EmailType.{Ham, Spam}
import smile.feature.Bag

import scala.concurrent.ExecutionContext
import scala.io.Source

object DictionaryResolver {
  val name = "DictionaryResolver"

  object Configuration {
    val configPath = "dictionary"
    val loadHamDictionaryPath = "load-ham-dictionary-path"
    val loadSpamDictionaryPath = "load-spam-dictionary-path"
    val takeUpTo = "take-up-to"
    val saveTo = "save-to"
  }

  def props(
    config: Config,
    loadDataManager: ActorRef,
    rootActor: ActorRef
  ): Props = Props(
    new DictionaryResolver(config, loadDataManager, rootActor)
  )

  case class DictionaryResolved(
    bag: String,
    bayesSize: Int)
      extends DictionaryResolverMessage
  case object ResolveDictionary extends DictionaryResolverMessage
  case class Dictionary(data: Array[String]) extends DictionaryResolverMessage
  sealed trait DictionaryResolverMessage
}

class DictionaryResolver(
  config: Config,
  loadDataManager: ActorRef,
  rootActor: ActorRef)
    extends Actor {
  implicit private val ec: ExecutionContext = context.dispatcher

  private val log = Logger(self.path.toStringWithoutAddress)
  private val upTo = config.getInt(Configuration.takeUpTo)
  private val saveTo = config.getString(Configuration.saveTo)
  private var bag: Bag[String] = _
  private val xStream = new XStream

  override def receive: Receive = {
    case Dictionary(dict) =>
      log.debug("\nTopWords\n" + dict.take(20).mkString("\n"))
      bag = new Bag[String](dict)

      rootActor ! DictionaryResolver.DictionaryResolved(xStream.toXML(bag), dict.length)

    case ResolveDictionary =>
      val hamResource = loadResources(
        Option(getClass.getResourceAsStream(config.getString(Configuration.loadHamDictionaryPath)))
      )
      val spamResource = loadResources(
        Option(getClass.getResourceAsStream(config.getString(Configuration.loadSpamDictionaryPath)))
      )

      val hamPath = loadWords(resolvePath(Configuration.loadHamDictionaryPath))

      val spamPath = loadWords(resolvePath(Configuration.loadSpamDictionaryPath))

      val resourceDict = hamResource
        .flatMap(
          ham =>
            spamResource
              .map(
                spam =>
                  Dictionary(
                    (ham.take(upTo) ++ spam.take(upTo)).toSet.toArray
                  )
              )
        )

      val pathDict = spamPath
        .flatMap(
          spam =>
            hamPath
              .map(
                ham =>
                  Dictionary(
                    (ham.take(upTo) ++ spam.take(upTo)).toSet.toArray
                  )
              )
        )

      pathDict
        .fold(resourceDict)(Some(_))
        .fold(
          context
            .actorOf(CleansedDataAccumulator.props()) ! CleansedDataAccumulator
            .CreateDictionary(loadDataManager)
        )(self !)

    case CleansedDataAccumulator.DataForDictionary(data) =>
      val groupedByType = data.groupBy(_.`type`)

      val hamFeatures = aggregateResults(
        groupedByType
          .getOrElse(Ham, Seq.empty)
      ).take(upTo)

      val spamFeatures = aggregateResults(
        groupedByType
          .getOrElse(Spam, Seq.empty)
      ).take(upTo)

      self ! Dictionary(
        (hamFeatures ++ spamFeatures).toSet.toArray
      )
      data
        .groupBy(_.`type`)
        .foreach {
          case (emailType, data) =>
            emailType match {
              case EmailType.Ham =>
                writeData(
                  new FileWriter(
                    new File(saveTo + "/ham-dictionary-" + Instant.now.toString + ".txt")
                  ),
                  data
                )
              case EmailType.Spam =>
                writeData(
                  new FileWriter(
                    new File(saveTo + "/spam-dictionary-" + Instant.now.toString + ".txt")
                  ),
                  data
                )
              case _ => ()
            }
        }
    case Done => throw new Exception("Reseting actor")
    case other =>
      log.debug("Got other of type {}, {}", other.getClass, other)

  }

  private def aggregateResults: Seq[CleansedData] => Seq[String] =
    _.flatMap(_.data)
      .foldLeft(Map.empty[String, Int]) {
        case (map, (word, occurrence)) =>
          map + (
            word -> map
              .get(word)
              .fold(occurrence)(_ + occurrence)
          )
      }
      .toSeq
      .sortWith(_._2 > _._2)
      .map(_._1)

  private def resolvePath(path: String): Option[String] =
    if (config.hasPath(path) && new File(config.getString(path)).isFile)
      Some(config.getString(path))
    else
      None

  private def loadResources(fis: Option[InputStream]): Option[Seq[String]] =
    fis flatMap { fis =>
      val scanner = new Scanner(fis).useDelimiter("\\A")
      if (scanner.hasNext) Some(scanner.next split "\n")
      else None
    }

  private def writeData(
    writer: FileWriter,
    data: Seq[CleansedData]
  ): Unit =
    try {
      writer.write(
        aggregateResults(data)
          .mkString("\n")
      )
    } finally {
      writer.close()
    }

  private def loadWords(path: Option[String]): Option[Seq[String]] =
    path
      .map(
        path => Source.fromFile(path).mkString.split("\n").toSeq
      )
}
