package eu.kohout.rest
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import eu.kohout.cleandata.CleanDataManager.{EmailRecognitionRequest, EmailRecognitionResponse, TrainRequest}

import scala.concurrent.{ExecutionContext, Future}

class HttpServerHandler(cleanDataManager: ActorRef)(implicit timeout: Timeout) {

  def train(entity: TrainRequest)(implicit ec: ExecutionContext): Future[Unit] =
    (cleanDataManager ? entity)
      .map(_ => ())

  def recognizeEmail(email: EmailRecognitionRequest)(implicit ec: ExecutionContext): Future[EmailRecognitionResponse] =
    (cleanDataManager ? email)
      .map {
        case resp: EmailRecognitionResponse =>
          resp
      }

}
