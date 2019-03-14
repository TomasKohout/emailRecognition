package eu.kohout.rest

object HttpMessages {

  object RootActor {
    case object StartCrossValidation
    case object TrainModel
    case object RestartActors
    case object StartActors
    case object Terminate
  }
  case class EmailRecognitionRequest(text: String) extends HttpMessage

  case class EmailRecognitionResponse(
    id: String,
    label: Labels,
    models: List[Model])
      extends HttpMessage

  case class Model(
    percent: Int,
    typeOfModel: ModelTypes)

  sealed trait HttpMessage
}
