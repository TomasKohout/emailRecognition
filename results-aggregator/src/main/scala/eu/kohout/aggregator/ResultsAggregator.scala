package eu.kohout.aggregator

import java.io.{File, PrintWriter}

import akka.Done
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

  case class AfterPrediction(
    id: String,
    realType: EmailType,
    predictedType: EmailType,
    percent: Int,
    models: List[Model])
      extends ResultsAggregatorMessages {
    override def toString: String = s"""
                                       |{
                                       | "id": "$id",
                                       | "before": "${realType.toString}",
                                       | "result": "${predictedType.toString}",
                                       | "result": "$percent",
                                       | "models":
                                       |    "${models.map { _.toString}.mkString("[", ",\n", "]")}"
                                       |}
                                       |""".stripMargin
  }

  case object WriteResults extends ResultsAggregatorMessages

  sealed trait ResultsAggregatorMessages
}

class ResultsAggregator extends Actor {
  private val log = Logger(self.path.toStringWithoutAddress)
  private var results: Seq[AfterPrediction] = Seq.empty

  private var cancellable: Option[Cancellable] = None

  //todo add configuration for results
  private val resultsDir = "/Users/tomaskohout/results"
  private var countOfResult = 0

  private def writeResults: Receive = {
    case result: AfterPrediction =>
      log.debug("After prediction for id {} received!", result.id)
      results = results :+ result

    case WriteResults =>
      val result = results
        .map { value =>

            (value.realType, value.predictedType, value.realType == value.predictedType)
        }

      val path1 = new File(resultsDir + "/" + "ResultsTable" + countOfResult + ".json")
      val path2 = new File(resultsDir + "/" + "ActualResults" + countOfResult + ".json")
      val writer1 = new PrintWriter(path1)
      val writer2 = new PrintWriter(path2)

      val table = result
        .map { res =>
          s"""
             | "${res._1}" | "${res._2}" | "${res._3}"
        """.stripMargin
        }
        .mkString("Before | After | Match", "\n", "")

      val matched = result.filter(_._3).count(_._3)

      try {
        writer1.write("Accuracy\n")
        writer1.write(matched.toDouble / result.size.toDouble * 100 + " %")
        writer1.write(table)
        writer2.write(mkStringFromResults)
      } finally {
        writer1.close()
        writer2.close()
        results = Seq.empty
        countOfResult = countOfResult + 1
      }

    case Done => throw new Exception("Reseting actor")

  }

  override def receive: Receive = writeResults

  private def mkStringFromResults: String =
    results
      .map (_.toString )
      .mkString("{ \n  \"data\": [", ",\n", "\n]}")

}
