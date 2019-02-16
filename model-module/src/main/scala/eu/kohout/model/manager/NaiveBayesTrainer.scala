package eu.kohout.model.manager
import akka.actor.Actor
import eu.kohout.model.manager.ModelManager.CleansedEmail
import smile.classification.NaiveBayes
import smile.classification.NaiveBayes.Model

class NaiveBayesTrainer(
  model: Model,
  sigma: Double = 1.0)
    extends Actor {
  var bayes: NaiveBayes = _
  override def receive: Receive = {
    case email: CleansedEmail =>
      bayes.learn(email.data, email.`type`.y)
  }

  override def preStart(): Unit = {
    super.preStart()
    bayes = new NaiveBayes(model, 2, 1, sigma)
  }
}
