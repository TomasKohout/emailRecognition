package eu.kohout.types
import eu.kohout.types.HttpMessages.EmailRecognitionResponse

object ResultsAggreagtorMessages {
  case class BeforePrediction(
    id: String,
    `type`: Labels)

  case class Result(
    beforePrediction: BeforePrediction,
    prediction: Option[EmailRecognitionResponse] = None)
}
