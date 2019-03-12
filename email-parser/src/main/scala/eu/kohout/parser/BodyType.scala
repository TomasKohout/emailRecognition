package eu.kohout.parser

sealed trait BodyType

object BodyType {

  case object HTML extends BodyType

  case object PLAIN extends BodyType
}
