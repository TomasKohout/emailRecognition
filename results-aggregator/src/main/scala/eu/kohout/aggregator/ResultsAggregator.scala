package eu.kohout.aggregator

import java.io.{File, PrintWriter}

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, PoisonPill, Props}
import akka.cluster.sharding.ShardRegion
import com.typesafe.scalalogging.Logger
import eu.kohout.aggregator.ResultsAggregator._
import eu.kohout.parser.EmailType

import scala.collection.mutable

object ResultsAggregator {

  val name = "ResultsAggregator"

  val idExtractor: ShardRegion.ExtractEntityId = {
    case msg => (name, msg)
  }

  val shardResolver: ShardRegion.ExtractShardId =
    _ => (math.abs(name.hashCode) % 100).toString

  def props: Props = Props(new ResultsAggregator)

  case class BeforePrediction(
    id: String,
    `type`: EmailType)
      extends ResultsAggregatorMessages

  case class AfterPrediction(
    id: String,
    `type`: EmailType,
    percent: Int,
    models: List[Model])
      extends ResultsAggregatorMessages

  case class Result(
    beforePrediction: BeforePrediction,
    prediction: Option[AfterPrediction] = None)
      extends ResultsAggregatorMessages

  case object WriteResults extends ResultsAggregatorMessages

  sealed trait ResultsAggregatorMessages
}

class ResultsAggregator extends Actor {
  private val log = Logger(self.path.toStringWithoutAddress)
  private val results: mutable.Map[String, Result] = mutable.Map.empty

  private var cancellable: Option[Cancellable] = None

  //todo add configuration for results
  private val resultsDir = "/Users/tomaskohout/results"
  private var countOfResult = 0

  private def writeResults: Receive = {
    case result: AfterPrediction =>
      log.debug("After prediction for id {} received!", result.id)
      log.debug("Before### {}", results.get(result.id))
      results
        .get(result.id)
        .map(_.copy(prediction = Some(result)))
        .map(results += result.id -> _)

      log.debug("After### {}", results.get(result.id))
      ()

    case beforePrediction: BeforePrediction =>
      log.debug("Before prediction for id {} received!", beforePrediction.id)
      results += (beforePrediction.id -> Result(beforePrediction))
      ()

    case WriteResults =>

      val result = results.toSeq
        .filter(_._2.prediction.isDefined)
        .map {
          case (key, value) =>
            val prediction = value.prediction.get
            val before = value.beforePrediction

            (before.`type`, prediction.`type`, before.`type` == prediction.`type`)

        }

      val path1 = new File(resultsDir + "/"  + "ResultsTable" + countOfResult + ".json")
      val writer1 = new PrintWriter(path1)

      val table = result
        .map { res =>
          s"""
             | "${res._1}" | "${res._2}" | "${res._3}"
        """.stripMargin
        }
        .mkString("Before | After | Match", "\n", "")

      val matched = result.filter(_._3).count(_._3)

      try {
        writer1.write(matched.toDouble / result.size.toDouble * 100 + " %")
        writer1.write(table)
      } finally {
        writer1.close()
        results.clear()
        countOfResult = countOfResult + 1
      }

  }

  override def receive: Receive = writeResults

  private def mkStringFromResults: String =
    results
      .map {
        case (id, result) =>
          s"""
             |{
             | "id": "$id",
             | "result": {
             |   "before": "${result.beforePrediction.`type`}",
             |   "result": "${result.prediction.fold("")(_.`type`.toString)}",
             |   "percent": "${result.prediction.fold("")(_.percent.toString)}"
             | }
             |}
          """.stripMargin

      }
      .mkString("{ \n  \"data\": [", ",\n", "\n]}")

}
