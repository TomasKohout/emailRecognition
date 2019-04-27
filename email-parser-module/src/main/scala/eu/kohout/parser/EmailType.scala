package eu.kohout.parser

import com.typesafe.scalalogging.Logger

object EmailType {

  private val log = Logger(getClass)

  case object Spam extends EmailType {
    override def y: Int = 0
    override def name: String = "spam"
  }
  case object Ham extends EmailType {
    override def y: Int = 1
    override def name: String = "ham"
  }
  case object NotObtained extends EmailType {
    override def y: Int = throw new IllegalStateException("NotObtained does not provide Y value!")
    override def name: String = "not_obtained"
  }

  def fromString(str: String): Option[EmailType] =
    str.toLowerCase match {
      case "ham"  => Some(Ham)
      case "spam" => Some(Spam)
      case other =>
        log.warn("{} is not ham nor spam", other)
        Some(NotObtained)
    }

  def makeString: EmailType => String = {
    case Ham         => "ham"
    case Spam        => "spam"
    case NotObtained => "not_obtained"
  }
}

sealed trait EmailType {
  def y: Int
  def name: String
}