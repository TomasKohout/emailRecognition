package eu.kohout.model.manager.traits

import com.thoughtworks.xstream.XStream
import com.typesafe.scalalogging.Logger
import smile.classification.OnlineClassifier

trait Trainer {
  var model: OnlineClassifier[Array[Double]]
  val serializer = new XStream
  var trainedTimes: Int = 0
  val log: Logger
}
