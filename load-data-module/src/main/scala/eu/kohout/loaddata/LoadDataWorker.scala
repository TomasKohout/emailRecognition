package eu.kohout.loaddata
import java.io.File

import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.Logger
import eu.kohout.parser.{EmailParser, EmailType}

object LoadDataWorker {

  def props(cleanDataManager: ActorRef): Props = Props(new LoadDataWorker(cleanDataManager))

  case class LoadData(
    email: File,
    label: EmailType)
      extends LoadDataWorkerMessage

  sealed trait LoadDataWorkerMessage
}

class LoadDataWorker(val cleanDataManager: ActorRef) extends Actor {
  import LoadDataWorker._

  private val log = Logger(self.path.address.toString)

  override def receive: Receive = {
    case loadData: LoadData =>
      log.debug("Load data message received {}, from {}", loadData.email.getName, sender().path.address.toString)
      cleanDataManager ! EmailParser.parseFromFile(loadData.email.getAbsolutePath, loadData.label)
  }
}
