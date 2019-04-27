package eu.kohout.aggregator

import java.io.{File, PrintWriter}

import akka.Done
import akka.actor.{Actor, Props}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.aggregator.ResultsAggregator._
import eu.kohout.parser.EmailType
import plotly.{Bar, Plotly}
import plotly.element.BarTextPosition
import plotly.layout.{BarMode, Layout}

object ResultsAggregator {

  val name = "ResultsAggregator"

  type IsCorrect = Boolean
  type Correct = Int
  type InCorrect = Int

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
    models: Seq[ModelResult])
      extends ResultsAggregatorMessages {
    override def toString: String = s"""
                                       |{
                                       | "id": "$id",
                                       | "before": "${realType.toString}",
                                       | "result": "${predictedType.toString}",
                                       | "prediction": $result,
                                       | "models":
                                       |    ${models.map { _.toString }.mkString("[", ",\n", "]")}
                                       |}
                                       |""".stripMargin
  }

  case object WriteResults extends ResultsAggregatorMessages

  case object WriteGraph extends ResultsAggregatorMessages

  sealed trait ResultsAggregatorMessages
}

class ResultsAggregator(config: Config) extends Actor {
  private val log = Logger(self.path.toStringWithoutAddress)
  private var results: Seq[AfterPrediction] = Seq.empty

  private val resultsDir = config.getString(Configuration.resultsDir)
  private var countOfResult = 0

  private var crossValidation: Map[Int, Seq[AfterPrediction]] = Map.empty

  private def writeResults: Receive = {
    case result: AfterPrediction =>
      results = results :+ result
      if (results.size % 100 == 0)
        log.info("Done prediction for so far {} emails", results.size)

    case WriteResults =>
      val result = results
        .map { value =>
          (
            value.realType,
            value.predictedType,
            value.realType == value.predictedType
          )
        }

      val path1 = new File(
        resultsDir + "/" + "ResultsTable" + countOfResult + ".json"
      )
      val path2 = new File(
        resultsDir + "/" + "ActualResults" + countOfResult + ".json"
      )
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
        writer1.write(
          "Accuracy: " + matched.toDouble / result.size.toDouble * 100 + " %\n"
        )
        writer1.write(table)
        writer2.write(mkStringFromResults)
      } finally {
        writer1.close()
        writer2.close()

        countOfResult = countOfResult + 1
        crossValidation = crossValidation + (countOfResult -> results)
        results = Seq.empty
      }

    case Done => throw new Exception("Reseting actor")

    case WriteGraph =>
      val layout = Layout(barmode = BarMode.Group)
      val graphValues = crossValidation
        .map {
          case (_, results) =>
            makeMap(results).toSeq
        }
        .reduceLeft(_ ++ _)
        .groupBy(_._1)
        .map {
          case (modelType, seq) =>
            modelType -> seq.map {
              case (_, (correct, inCorrect)) =>
                correct -> inCorrect
            }
        }
        .map {
          case (modelType, seq) => (plot(modelType, seq.map(_._1)), plot(modelType, seq.map(_._2)))
        }

      Plotly.plot(resultsDir + "/" + "incorectGraph.html", graphValues.map(_._2).toSeq, layout)
      Plotly.plot(resultsDir + "/" + "correctGraph.html", graphValues.map(_._1).toSeq, layout)

      crossValidation = Map.empty
  }

  override def receive: Receive = writeResults

  private def mkStringFromResults: String =
    results
      .map(_.toString)
      .mkString("{ \n  \"data\": [", ",\n", "\n]}")

  def makeMap: Seq[AfterPrediction] => Map[ModelType, (Correct, InCorrect)] =
    _.flatMap { data =>
      data.models.map { model =>
        model.typeOfModel -> (data.realType.y == model.result)
      }
    }.groupBy(_._1)
      .map {
        case (modelType, list) =>
          modelType -> countResults(list)
      }

  def countResults: Seq[(ModelType, IsCorrect)] => (Correct, InCorrect) =
    _.foldLeft(0, 0) {
      case ((right, left), (_, isCorrect)) =>
        if (isCorrect)
          (right + 1, left)
        else
          (right, left + 1)

    }

  def plot: (ModelType, Iterable[Int]) => Bar = { (modelType, results) =>
    val (names, _) = results.foldLeft(Seq.empty[String], results.size) {
      case ((seq, round), _) =>
        (("Cyklus " + round) +: seq) -> (round - 1)
    }

    Bar(
      x = names,
      y = results.toSeq,
      name = modelType,
      text = results.map(_.toString).toSeq,
      textposition = BarTextPosition.Auto
    )

  }
}
