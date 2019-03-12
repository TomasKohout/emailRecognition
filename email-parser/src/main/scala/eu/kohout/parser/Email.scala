package eu.kohout.parser
import akka.actor.ActorRef

case class Email(
  bodyParts: Seq[BodyPart],
  id: String,
  `type`: EmailType,
  replyTo: Option[ActorRef] = None)
