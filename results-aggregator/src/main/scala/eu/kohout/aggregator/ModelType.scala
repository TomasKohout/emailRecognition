package eu.kohout.aggregator

object ModelType {

  case object SVM extends ModelType {
    override def toString: String = "SVM"
  }

  case object NaiveBayes extends ModelType {
    override def toString: String = "NaiveBayes"
  }

  case object AdaBoost extends ModelType {
    override def toString: String = "AdaBoost"
  }

  implicit def makeString: ModelType => String = _.toString

  def apply: String => ModelType = {
    case "SVM"           => SVM
    case "NaiveBayes"    => NaiveBayes
    case "AdaBoost"      => AdaBoost
    case other =>
      throw new IllegalStateException(s"$other is not a valid model type")
  }
}

sealed trait ModelType
