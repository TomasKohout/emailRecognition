package eu.kohout.types

object ModelTypes {

  case object SVM extends ModelTypes

  case object NaiveBayes extends ModelTypes

  def apply: String => ModelTypes = {
    case "SVM"        => SVM
    case "NaiveBayes" => NaiveBayes
    case other =>
      throw new IllegalStateException(s"$other is not a valid model type")
  }

}

sealed trait ModelTypes
