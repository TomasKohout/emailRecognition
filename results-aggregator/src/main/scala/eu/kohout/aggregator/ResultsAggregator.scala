package eu.kohout.aggregator

import java.io.{File, PrintWriter}

import akka.Done
import akka.actor.{Actor, Cancellable, Props}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.aggregator.ResultsAggregator._
import eu.kohout.parser.EmailType

object ResultsAggregator {

  val name = "ResultsAggregator"

  object Configuration {
    val configPath = "results-aggregator"
    val resultsDir = "results-directory"
  }

  def props(config: Config): Props = Props(new ResultsAggregator(config))

  case class AfterPrediction(
    id: String,
    realType: EmailType,
    predictedType: EmailType,
    result: Int,
    models: Seq[Model])
      extends ResultsAggregatorMessages {
    override def toString: String = s"""
                                       |{
                                       | "id": "$id",
                                       | "before": "${realType.toString}",
                                       | "result": "${predictedType.toString}",
                                       | "prediction": $result,
                                       | "models":
                                       |    ${models.map { _.toString}.mkString("[", ",\n", "]")}
                                       |}
                                       |""".stripMargin
  }

  case object WriteResults extends ResultsAggregatorMessages

  sealed trait ResultsAggregatorMessages
}

class ResultsAggregator(config: Config) extends Actor {
  private val log = Logger(self.path.toStringWithoutAddress)
  private var results: Seq[AfterPrediction] = Seq.empty

  private val resultsDir = config.getString(Configuration.resultsDir)
  private var countOfResult = 0

  private def writeResults: Receive = {
    case result: AfterPrediction =>
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
        writer1.write("Accuracy: " + matched.toDouble / result.size.toDouble * 100 + " %\n")
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
