package eu.kohout.rest

object HttpMessages {
  case class EmailRecognitionRequest(text: String) extends HttpMessage

  case class EmailRecognitionResponse(
    id: String,
    label: Labels,
    percent: Int,
    models: List[Model])
      extends HttpMessage

  case class Model(
    percent: Int,
    typeOfModel: ModelTypes)

  case class TrainRequest(
    modelType: Seq[ModelTypes],
    data: List[TrainData])
      extends HttpMessage

  case class TrainData(
    label: Labels,
    message: String)

  sealed trait HttpMessage
}
