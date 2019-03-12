package eu.kohout.model.manager.traits

import com.thoughtworks.xstream.XStream
import com.typesafe.scalalogging.Logger
import smile.classification.Classifier

trait Predictor {
  var model: Option[Classifier[Array[Double]]] = None
  val serializer = new XStream
  val log: Logger
}
