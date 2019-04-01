package eu.kohout.parser
import java.io.{ByteArrayInputStream, File, FileInputStream, InputStream}
import java.time.Instant
import java.util.{Scanner, UUID}

import com.typesafe.scalalogging.Logger
import eu.kohout.parser.BodyType.{HTML, PLAIN}
import org.apache.james.mime4j.codec.DecodeMonitor
import org.apache.james.mime4j.message.DefaultBodyDescriptorBuilder
import org.apache.james.mime4j.parser.MimeStreamParser
import org.apache.james.mime4j.stream.MimeConfig
;

object EmailParser {

  private val log = Logger(getClass)

  implicit private def streamToString(fis: InputStream): Option[String] = {
    val scanner = new Scanner(fis).useDelimiter("\\A")
    if (scanner.hasNext) scanner.next else None
  }

  implicit private def toOption[T](what: T): Option[T] = Option(what)

  private val MessageId = "Message-ID"
  private val Subject = "Subject"

  def parseFromFile(
    file: File,
    emailType: EmailType
  ): Email = {
    log.debug("Parsing email path: {}", file)
    val fis = new FileInputStream(file)
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
    val subject = email.flatMap(_.getHeader).flatMap(_.getField(Subject)).flatMap(_.getBody).getOrElse("")

    val htmlBody: Option[String] = email.flatMap(_.getHTMLEmailBody).flatMap(_.getIs)
    val plainBody: Option[String] = email.flatMap(_.getPlainTextEmailBody).flatMap(_.getIs).fold(subject)(subject + "\n" + _ )

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
