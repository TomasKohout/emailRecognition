package eu.kohout.dictionary
import akka.actor.{Actor, ActorRef, Cancellable, Props}
import eu.kohout.dictionary.CleansedDataAccumulator.{CreateDictionary, SendDictionary}
import com.typesafe.scalalogging.Logger
import eu.kohout.cleandata.CleanDataManager.CleansedData
import eu.kohout.loaddata.LoadDataManager.CreateDictionaryFromData

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object CleansedDataAccumulator {
  case class CreateDictionary(loadDataManager: ActorRef) extends CleansedDataAccumulatorMessage
  private case object SendDictionary extends CleansedDataAccumulatorMessage
  case class DataForDictionary(data: Seq[CleansedData]) extends CleansedDataAccumulatorMessage

  sealed trait CleansedDataAccumulatorMessage

  def props(): Props = Props(new CleansedDataAccumulator)
}

/** This actor will kill self after resolving all cleansed emails from which dictionary will be created.
  *
  */
class CleansedDataAccumulator extends Actor {

  implicit val ec: ExecutionContext = context.dispatcher

  private val log = Logger(self.path.toStringWithoutAddress)

  private var cleansedData: Seq[CleansedData] = Seq.empty
  private var sendToDictionary: Option[Cancellable] = None

  private def cancelCancellable(cancellable: Cancellable): Boolean =
    if (cancellable.cancel() || cancellable.isCancelled) true
    else cancelCancellable(cancellable)

  override def receive: Receive = {
    case CreateDictionary(loadDataManager) =>
      log.debug("Sending CreateDictionaryFromData to loadDataManager")

      loadDataManager ! CreateDictionaryFromData

    case data: CleansedData =>
      sendToDictionary = sendToDictionary.fold(
        Some(
          context.system.scheduler
            .scheduleOnce(delay = 5 minutes, message = SendDictionary, receiver = self)
        )
      ) { cancellable =>
        cancelCancellable(cancellable)
        Some(
          context.system.scheduler.scheduleOnce(
            delay = 5 minutes,
            message = SendDictionary,
            receiver = self
          )
        )
      }

      cleansedData = cleansedData :+ data
      if(cleansedData.size % 1000 == 0)
        log.info("Size of accumulated data for dictionary: {}", cleansedData.size)


    case SendDictionary =>
      log.debug("Dictionary resolved! size of cleansedData {}", cleansedData.size)
      context.parent ! CleansedDataAccumulator.DataForDictionary(cleansedData)
      context.stop(self)

  }
}
