package eu.kohout.loaddata
import java.io.File

import akka.actor.{Actor, ActorRef, Props}
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

  override def receive: Receive = {
    case loadData: LoadData =>
      cleanDataManager ! EmailParser.parse(loadData.email.getAbsolutePath, loadData.label)
  }
}
