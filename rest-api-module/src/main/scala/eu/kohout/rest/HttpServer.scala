package eu.kohout.rest

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpResponse, ResponseEntity, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.Config
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import eu.kohout.rest.HttpMessages.EmailRecognitionRequest
import eu.kohout.rest.HttpServer.Configuration
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

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
  implicit actorSystem: ActorSystem)
    extends FailFastCirceSupport {

  implicit val materializer: Materializer = ActorMaterializer()

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

            onComplete(response) {
              case Success(value) => complete(HttpResponse(entity = value))
              case Failure(ex) =>
                complete(
                  StatusCodes.BadRequest,
                  Json
                    .obj(
                      "error" -> ex.getMessage.asJson,
                      "stack" -> ex.getStackTrace.map(_.toString).asJson
                    )
                    .toString()
                )
            }

          }
        }
      }
    } ~
      get {
        path("application" / "!crossValidation") {
          extractExecutionContext { implicit ec =>
            val response = httpServerHandler
              .crossValidation()
              .flatMap(_ => Marshal(Json.obj()).to[ResponseEntity])

            onSuccess(response) { responseEntity =>
              complete(HttpResponse(entity = responseEntity))
            }
          }
        }
      } ~
      get {
        path("application" / "!trainModels") {
          extractExecutionContext { implicit ec =>
            val response = httpServerHandler
              .trainModels()
              .flatMap(_ => Marshal(Json.obj()).to[ResponseEntity])

            onSuccess(response) { responseEntity =>
              complete(HttpResponse(entity = responseEntity))
            }
          }
        }
      } ~
      get {
        path("application" / "!killActors") {
          extractExecutionContext { implicit ec =>
            val response = httpServerHandler
              .killActors()
              .flatMap(_ => Marshal(Json.obj()).to[ResponseEntity])

            onSuccess(response) { responseEntity =>
              complete(HttpResponse(entity = responseEntity))
            }
          }
        }
      } ~
      get {
        path("application" / "!startActors") {
          extractExecutionContext { implicit ec =>
            val response = httpServerHandler
              .startActors()
              .flatMap(_ => Marshal(Json.obj()).to[ResponseEntity])

            onSuccess(response) { responseEntity =>
              complete(HttpResponse(entity = responseEntity))
            }
          }
        }
      } ~
      get {
        path("application" / "!terminate") {
          extractExecutionContext { implicit ec =>
            val response = httpServerHandler
              .terminate()
              .flatMap(_ => Marshal(Json.obj()).to[ResponseEntity])

            onSuccess(response) { responseEntity =>
              complete(HttpResponse(entity = responseEntity))
            }
          }
        }
      }

  }

  private var bindingFuture: Future[ServerBinding] = Http().bindAndHandle(routes, address, port)
}
