package eu.kohout.dictionary

import java.io.{File, FileWriter}

import akka.actor.{Actor, ActorRef, Props}
import akka.cluster.sharding.ShardRegion
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager
import eu.kohout.dictionary.DictionaryResolver.{Configuration, Dictionary, ResolveDictionary}
import eu.kohout.loaddata.LoadDataManager.CreateDictionaryFromData
import eu.kohout.parser.EmailType.{Ham, Spam}
import smile.feature.Bag

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source

object DictionaryResolver {
  val name = "DictionaryResolver"

  object Configuration {
    val configPath = "dictionary"
    val loadDictionaryPath = "load-dictionary-path"
    val takeUpTo = "take-up-to"
    val timeOut = "timeout"
    val saveTo = "save-to"
  }

  val idExtractor: ShardRegion.ExtractEntityId = {
    case msg => (name, msg)
  }

  val shardResolver: ShardRegion.ExtractShardId =
    _ => (math.abs(name.hashCode) % 100).toString

  def props(
    config: Config,
    loadDataManager: ActorRef,
    rootActor: ActorRef
  ): Props = Props(
    new DictionaryResolver(config, loadDataManager, rootActor)
  )

  case class DictionaryResolved(bag: Bag[String], bayesSize: Int) extends DictionaryResolverMessage
  case object ResolveDictionary extends DictionaryResolverMessage
  case class Dictionary(data: Array[String]) extends DictionaryResolverMessage
  sealed trait DictionaryResolverMessage
}

class DictionaryResolver(
  config: Config,
  loadDataManager: ActorRef,
  rootActor: ActorRef)
    extends Actor {
  implicit private val createDictionaryTimeout: Timeout = config.getDuration(Configuration.timeOut).getSeconds seconds
  implicit private val ec: ExecutionContext = context.dispatcher

  private val log = Logger(self.path.toStringWithoutAddress)
  private val upTo = config.getInt(Configuration.takeUpTo)
  private val saveTo = config.getString(Configuration.saveTo)
  private var dictionary: Seq[String] = Seq.empty
  private var bag: Bag[String] = _

  override def receive: Receive = {
    case Dictionary(dict) =>
      val shrinkedDict = dict.take(upTo)
      bag = new Bag[String](shrinkedDict)

      rootActor ! DictionaryResolver.DictionaryResolved(bag, shrinkedDict.length)


      val printer = new FileWriter(new File(saveTo))

      try {
        printer.write(dictionary.mkString("\n"))
      } finally {
        printer.close()
      }


    case ResolveDictionary =>
      val dictionaryPath =
        if (config.hasPath(Configuration.loadDictionaryPath))
          Some(new File(config.getString(Configuration.loadDictionaryPath)))
        else
          None

      for {
        dictionary <- dictionaryPath.fold(createDictionary)(loadDictionary)
      } yield self ! dictionary

      ()

  }

  private def createDictionary: Future[Dictionary] =
    (loadDataManager ? CreateDictionaryFromData)
      .map {
        case CleanDataManager.Dictionary(data) =>
          dictionary = data.flatMap(_.data).map(_._1)

          val groupedByType = data.groupBy(_.`type`)

          val hamFeatures = groupedByType
            .getOrElse(Ham, Seq.empty)
            .flatMap(_.data)
            .sortWith(_._2 > _._2)
            .take(upTo)
            .map(_._1)

          val spamFeatures = groupedByType
            .getOrElse(Spam, Seq.empty)
            .flatMap(_.data)
            .sortWith(_._2 > _._2)
            .take(upTo)
            .map(_._1)

          Dictionary(
            hamFeatures
              .intersect(spamFeatures)
              .foldLeft((hamFeatures ++ spamFeatures).toSet)((featuresSet, string) => featuresSet - string)
              .toArray
          )
      }

  private def loadDictionary: File => Future[Dictionary] = { file =>

    Future.successful {
      val res = Source
        .fromFile(file)
        .getLines()
        .toSet
        .toArray
        .map(_.trim())
      log.info("FILE: {}", file.getAbsolutePath)
      Dictionary(
        res
      )
    }
  }
}
