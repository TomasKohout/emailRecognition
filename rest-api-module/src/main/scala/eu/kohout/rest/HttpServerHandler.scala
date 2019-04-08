package eu.kohout.rest

import akka.actor.ActorRef
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

import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class HttpServerHandler(rootActor: ActorRef)(implicit timeout: Timeout) {
  val log = Logger(getClass)

  def recognizeEmail(
    email: EmailRecognitionRequest
  )(
    implicit ec: ExecutionContext
  ): Future[EmailRecognitionResponse] =
    Try(
      HttpMessages.RootActor
        .PredictionData(
          EmailParser
            .parseFromString(
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
              x => Model(percent = x.percent, typeOfModel = ModelTypes.fromString(x.typeOfModel))
            )
          )
        )

      case HttpMessages.RootActor.NotTrained =>
        Future.failed(new Exception("Could not predict email. Models are not trained"))
    }

  def crossValidation(): Future[Unit] =
    Future.successful(rootActor ! RootActor.StartCrossValidation)

  def restart(): Future[Unit] = Future.successful(rootActor ! RootActor.RestartActors)
  def terminate(): Future[Unit] = Future.successful(rootActor ! RootActor.Terminate)
  def start(): Future[Unit] = Future.successful(rootActor ! RootActor.StartApplication)
  def trainModels(): Future[Unit] = Future.successful(rootActor ! RootActor.TrainModel)

}
