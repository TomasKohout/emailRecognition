package eu.kohout.rest

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import eu.kohout.aggregator.ResultsAggregator.AfterPrediction
import eu.kohout.parser.{EmailParser, EmailType}
import eu.kohout.rest.HttpMessages.{
  EmailRecognitionRequest,
  EmailRecognitionResponse,
  Model,
  RootActor
}
import java.util.Base64

import akka.cluster.Cluster
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class HttpServerHandler(
  rootActor: ActorRef,
  actorSystem: ActorSystem
)(
  implicit timeout: Timeout) {
  val log = Logger(getClass)

  val cluster = Cluster(actorSystem)

  def recognizeEmail(
    email: EmailRecognitionRequest
  )(
    implicit ec: ExecutionContext
  ): Future[EmailRecognitionResponse] =
    Try(
      HttpMessages.RootActor
        .PredictionData(
          EmailParser
            .parse(
              new String(Base64.getDecoder.decode(email.text)),
              EmailType.NotObtained
            )
        )
    ) fold (
      ex => Future.failed(ex),
      rootActor ?
    ) flatMap {
      case resp: AfterPrediction =>
        Future.successful(
          EmailRecognitionResponse(
            id = resp.id,
            label = Labels.fromString(resp.predictedType.name),
            models = resp.models.map(
              x => Model(percent = x.result, typeOfModel = ModelTypes.fromString(x.typeOfModel))
            )
          )
        )

      case HttpMessages.RootActor.NotTrained =>
        Future.failed(new Exception("Could not predict email. Models are not trained"))
    }

  def crossValidation(): Future[Unit] =
    Future.successful(rootActor ! RootActor.StartCrossValidation)

  def restart(): Future[Unit] = Future.successful(rootActor ! RootActor.RestartActors)

  def terminate(): Future[Unit] = {
    log.info("Leaving cluster now, bye.")

    Future.successful(cluster.leave(cluster.selfAddress))
  }
  def start(): Future[Unit] = Future.successful(rootActor ! RootActor.StartApplication)
  def trainModels(): Future[Unit] = Future.successful(rootActor ! RootActor.TrainModel)

}
