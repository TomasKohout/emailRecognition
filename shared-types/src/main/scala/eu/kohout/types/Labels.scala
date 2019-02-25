package eu.kohout.types

object Labels {
  case object Spam extends Labels
  case object Ham extends Labels
  case object NotObtained extends Labels

  implicit def fromString: String => Labels = {
    case "ham"          => Ham
    case "spam"         => Spam
    case "not_obtained" => NotObtained
    case other          => throw new IllegalStateException(s"$other is not a valid Label!")
  }

  implicit def makeString: Labels => String = {
    case Spam        => "spam"
    case Ham         => "ham"
    case NotObtained => "not_obtained"
  }
}

sealed trait Labels
