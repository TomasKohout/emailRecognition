package eu.kohout.rest
import enumeratum._

sealed trait ModelTypes extends EnumEntry

case object ModelTypes extends Enum[ModelTypes] with CirceEnum[ModelTypes]{

  val values = findValues

  case object SVM extends ModelTypes

  case object NaiveBayes extends ModelTypes

  case object AdaBoost extends ModelTypes
  case object KNN extends ModelTypes

  def fromString: String => ModelTypes = {
    case x if x.toLowerCase() == "svm" => SVM
    case x if x.toLowerCase() == "naivebayes" => NaiveBayes
    case x if x.toLowerCase() == "adaboost" => AdaBoost
    case x if x.toLowerCase() == "knn" => KNN
    case other =>
      throw new IllegalStateException(s"$other is not a valid model type")
  }

  def apply: String => ModelTypes = {
    case x if x.toLowerCase() == "svm" => SVM
    case x if x.toLowerCase() == "naivebayes" => NaiveBayes
    case x if x.toLowerCase() == "adaboost" => AdaBoost
    case x if x.toLowerCase() == "knn" => KNN
    case other =>
      throw new IllegalStateException(s"$other is not a valid model type")
  }
}



