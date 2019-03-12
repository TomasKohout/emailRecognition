package eu.kohout.rest
import enumeratum.{CirceEnum, Enum, EnumEntry}

sealed trait Labels extends EnumEntry

object Labels extends Enum[ModelTypes] with CirceEnum[ModelTypes] {
  val values = findValues

  case object Spam extends Labels
  case object Ham extends Labels
  case object NotObtained extends Labels

  implicit def fromString: String => Labels = {
    case x if x.toLowerCase() == "ham"          => Ham
    case x if x.toLowerCase() == "spam"         => Spam
    case x if x.toLowerCase() == "not_obtained" => NotObtained
    case other                                  => throw new IllegalStateException(s"$other is not a valid Label!")
  }

  implicit def makeString: Labels => String = {
    case Spam        => "spam"
    case Ham         => "ham"
    case NotObtained => "not_obtained"
  }
}
