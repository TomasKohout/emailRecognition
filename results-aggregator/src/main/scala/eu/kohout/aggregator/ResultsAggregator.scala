package eu.kohout.aggregator
import java.io.{File, PrintWriter}
import java.time.Instant

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import eu.kohout.aggregator.ResultsAggregator.{PrintResults, Result}
import eu.kohout.types.HttpMessages.EmailRecognitionResponse
import eu.kohout.types.Labels
import eu.kohout.types.ResultsAggreagtorMessages.BeforePrediction

import scala.collection.mutable
import scala.concurrent.duration._

object ResultsAggregator {

  val name = "ResultsAggregator"

  def asClusterSingleton(
    props: Props,
    appCfg: Config,
    system: ActorSystem
  ): ActorRef = {

    val singleton = system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = props,
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system)
      ),
      name = name
    )

    system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = singleton.path.toStringWithoutAddress,
        settings = ClusterSingletonProxySettings(system)
      ),
      name = name + "Proxy"
    )
  }

  def props: Props = Props(new ResultsAggregator)

  case class Result(
    beforePrediction: BeforePrediction,
    prediction: Option[EmailRecognitionResponse] = None)

  private case object PrintResults
}

class ResultsAggregator extends Actor {
  private val log = Logger(self.path.toStringWithoutAddress)
  private val results: mutable.Map[String, Result] = mutable.Map.empty

  //todo add configuration for results
  private val resultsDir = "/Users/tomaskohout/results"

  override def receive: Receive = {
    case result: EmailRecognitionResponse =>
      log.debug("After prediction for id {} received!", result.id)
      log.debug("Before$$$ {}", results.get(result.id))
      results
        .get(result.id)
        .map(_.copy(prediction = Some(result)))
        .map(results += result.id -> _)

      log.debug("After$$$ {}", results.get(result.id))
      ()

    case beforePrediction: BeforePrediction =>
      log.debug("Before prediction for id {} received!", beforePrediction.id)
      results += (beforePrediction.id -> Result(beforePrediction))
      ()

    case PrintResults =>
      val path = new File(resultsDir + "/" + Instant.now().toString + ".json")
      val writer = new PrintWriter(path)

      val result = results
        .toSeq
        .filter(_._2.prediction.isDefined)
        .map{
          case (key, value) =>
            val prediction = value.prediction.get
            val before = value.beforePrediction

            (before.`type`, prediction.label, before.`type` == prediction.label)

        }

      val path1 = new File(resultsDir + "/" + Instant.now().toString + "Results.json")
      val writer1 = new PrintWriter(path1)

      val table = result.map{ res =>
        s"""
          | "${res._1}" | "${res._2}" | "${res._3}"
        """.stripMargin
      }.mkString("Before | After | Match", "\n", "")

      val matched = result.filter(_._3).count(_._3)


      try {
        writer1.write(matched.toDouble / result.size.toDouble * 100 + " %")
        writer1.write(table)
        writer.write(mkStringFromResults)
      } finally {
        writer1.close()
        writer.close()
      }

  }

  private def mkStringFromResults: String =
    results
      .map {
        case (id, result) =>
          s"""
             |{
             | "id": "$id",
             | "result": {
             |   "before": "${result.beforePrediction.`type`}",
             |   "result": "${result.prediction.fold("")(_.label)}",
             |   "percent": "${result.prediction.fold("")(_.percent.toString)}"
             | }
             |}
          """.stripMargin

      }
      .mkString("{ \n  \"data\": [", ",\n", "\n]}")

  override def preStart(): Unit = {
    super.preStart()

    context.system.scheduler.schedule(2 minutes, 30 seconds, self, PrintResults)(context.dispatcher)
    ()
  }
}
