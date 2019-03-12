package eu.kohout.rest

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpResponse, ResponseEntity}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.stream.Materializer
import com.typesafe.config.Config
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import eu.kohout.rest.HttpMessages.{EmailRecognitionRequest, TrainRequest}
import eu.kohout.rest.HttpServer.Configuration
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._

import scala.concurrent.{ExecutionContext, Future}

object HttpServer {

  object Configuration {
    private val cfg = "http"
    val port = s"$cfg.port"
    val address = s"$cfg.address"
  }
}

class HttpServer(
  config: Config,
  httpServerHandler: HttpServerHandler
)(
  implicit actorSystem: ActorSystem,
  materialize: Materializer)
    extends FailFastCirceSupport {

  private val port = config.getInt(Configuration.port)
  require(port >= 0 && port <= 65535, "Port has to be between 0 and 65535!")
  private val address = config.getString(Configuration.address)

  private val routes: Route = {
    post {
      path("email-recognition") {
        extractExecutionContext { implicit ec =>
          entity(as[EmailRecognitionRequest]) { email =>
            val response = httpServerHandler
              .recognizeEmail(email)
              .flatMap(result => Marshal(result.asJson).to[ResponseEntity])
            onSuccess(response) { responseEntity =>
              complete(HttpResponse(entity = responseEntity))
            }
          }
        }
      }
    } ~
      post {
        path("models" / "!train") {
          extractExecutionContext { implicit ec =>
            entity(as[TrainRequest]) { entity =>
              val response = httpServerHandler
                .train(entity)
                .flatMap(_ => Marshal(Json.obj()).to[ResponseEntity])

              onSuccess(response) { responseEntity =>
                complete(HttpResponse(entity = responseEntity))
              }
            }
          }
        }
      }
  }

  private val bindingFuture = Http().bindAndHandle(routes, address, port)

  def stop(implicit ec: ExecutionContext): Future[Done] =
    bindingFuture.flatMap(_.unbind())
}
