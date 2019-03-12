package eu.kohout.rest

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import eu.kohout.aggregator.ResultsAggregator.AfterPrediction
import eu.kohout.cleandata.CleanDataManager
import eu.kohout.parser.{EmailParser, EmailType}
import eu.kohout.rest.HttpMessages.{EmailRecognitionRequest, EmailRecognitionResponse, Model, RootActor}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class HttpServerHandler(
  cleanDataManager: ActorRef,
  rootActor: ActorRef
)(
  implicit timeout: Timeout) {

  def recognizeEmail(email: EmailRecognitionRequest)(implicit ec: ExecutionContext): Future[EmailRecognitionResponse] =
    try {
      Try(CleanDataManager.CleanData(EmailParser.parseFromString(email.text, EmailType.NotObtained)))
        .fold(
          ex => Future.failed(ex),
          cleanDataManager ? _
        )
        .map {
          case resp: AfterPrediction =>
            EmailRecognitionResponse(
              id = resp.id,
              label = Labels.fromString(resp.`type`.name),
              models = resp.models.map(x => Model(percent = x.percent, typeOfModel = ModelTypes.fromString(x.typeOfModel)))
            )
        }
    } catch {
      case th: Throwable => Future.failed(th)
    }

  def trainModels(): Future[Unit] = Future.successful(rootActor ! RootActor.TrainModel)

  def crossValidation(): Future[Unit] =
    Future.successful(rootActor ! RootActor.StartCrossValidation)

  def killActors(): Future[Unit] = Future.successful(rootActor ! RootActor.KillActors)
  def startActors(): Future[Unit] = Future.successful(rootActor ! RootActor.StartActors)
}
