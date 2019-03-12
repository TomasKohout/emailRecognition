package eu.kohout.aggregator

object ModelType {

  case object SVM extends ModelType {
    override def toString: String = "SVM"
  }

  case object NaiveBayes extends ModelType {
    override def toString: String = "NaiveBayes"
  }

  def apply: String => ModelType = {
    case "SVM"        => SVM
    case "NaiveBayes" => NaiveBayes
    case other =>
      throw new IllegalStateException(s"$other is not a valid model type")
  }

}

sealed trait ModelType