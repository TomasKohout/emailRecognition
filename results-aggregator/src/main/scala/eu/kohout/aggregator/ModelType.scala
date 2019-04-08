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

  case object KNN extends ModelType {
    override def toString: String = "KNN"
  }

  implicit def makeString: ModelType => String = _.toString

  def apply: String => ModelType = {
    case model if model.toLowerCase() == "svm" => SVM
    case model if model.toLowerCase() == "naivebayes" => NaiveBayes
    case model if model.toLowerCase() == "adaboost" => AdaBoost
    case model if model.toLowerCase() == "knn" => KNN
    case other =>
      throw new IllegalStateException(s"$other is not a valid model type")
  }
}

sealed trait ModelType
