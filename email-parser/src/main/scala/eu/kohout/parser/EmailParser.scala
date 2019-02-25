package eu.kohout.parser
import java.io.{ByteArrayInputStream, FileInputStream, InputStream}
import java.time.Instant
import java.util.{Scanner, UUID}

import akka.actor.ActorRef
import com.sun.net.httpserver.Authenticator.Failure
import com.typesafe.scalalogging.Logger
import org.apache.james.mime4j.codec.DecodeMonitor
import org.apache.james.mime4j.message.DefaultBodyDescriptorBuilder
import org.apache.james.mime4j.parser.MimeStreamParser
import org.apache.james.mime4j.stream.MimeConfig

sealed trait BodyType

case object HTML extends BodyType

case object PLAIN extends BodyType

case class BodyPart(
  `type`: BodyType,
  body: String)

object EmailType {

  private val log = Logger(getClass)

  case object Spam extends EmailType {
    override val y: Int = 0
    override def name: String = "spam"
  }
  case object Ham extends EmailType {
    override val y: Int = 1
    override def name: String = "ham"
  }
  case object NotObtained extends EmailType {
    override val y: Int = throw new IllegalStateException("NotObtained does not provide Y value!")
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

case class Email(
  bodyParts: Seq[BodyPart],
  id: String,
  `type`: EmailType,
  replyTo: Option[ActorRef] = None)

object EmailParser {

  private val log = Logger(getClass)

  implicit private def streamToString(fis: InputStream): Option[String] = {
    val scanner = new Scanner(fis).useDelimiter("\\A")
    if (scanner.hasNext) scanner.next else None
  }

  implicit private def toOption[T](what: T): Option[T] = Option(what)

  private val MessageId = "Message-ID"

  def parseFromFile(
    path: String,
    emailType: EmailType
  ): Email = {
    log.debug("Parsing email path: {}", path)
    val fis = new FileInputStream(path)
    parse(fis, emailType)
  }

  private def parse(
    is: InputStream,
    emailType: EmailType
  ): Email = {
    val contentHandler = new tech.blueglacier.parser.CustomContentHandler()
    val mime4jConfig = MimeConfig.DEFAULT
    val bodyDescriptorBuilder = new DefaultBodyDescriptorBuilder()

    val parser = new MimeStreamParser(mime4jConfig, DecodeMonitor.SILENT, bodyDescriptorBuilder)
    parser.setContentDecoding(true)
    parser.setContentHandler(contentHandler)

    try {
      parser.parse(is)
    } finally {
      is.close()
    }

    val email = Option(contentHandler.getEmail)

    log.debug("Email parsed {}", email.isDefined)
    val htmlBody: Option[String] = email.flatMap(_.getHTMLEmailBody).flatMap(_.getIs)
    val plainBody: Option[String] = email.flatMap(_.getPlainTextEmailBody).flatMap(_.getIs)

    val parts = htmlBody.fold(Seq.empty[BodyPart])(part => Seq(BodyPart(HTML, part))) ++
      plainBody.fold(Seq.empty[BodyPart])(part => Seq(BodyPart(PLAIN, part)))

    val id = email
      .flatMap(_.getHeader)
      .flatMap(_.getField(MessageId))
      .flatMap(_.getBody)
      .getOrElse(UUID.randomUUID() + "---" + Instant.now().toEpochMilli.toString)

    log.debug("Email have htmlBody: {}, plainBody: {} and id: {}", htmlBody.isDefined, plainBody.isDefined, id)

    Email(
      bodyParts = parts,
      `type` = emailType,
      id = id
    )
  }

  def parseFromString(
    message: String,
    emailType: EmailType
  ): Email = parse(new ByteArrayInputStream(message.getBytes), emailType)

}
