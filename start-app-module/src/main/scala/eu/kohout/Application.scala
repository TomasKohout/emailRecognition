package eu.kohout

import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.{AbstractModule, Guice, Injector, Key}
import com.typesafe.scalalogging.Logger
import eu.kohout.actorsystem.ActorSystemModule
import eu.kohout.aggregator.ResultsAggregatorModule
import eu.kohout.cleandata.CleanDataModule
import eu.kohout.config.ConfigModule
import eu.kohout.loaddata.LoadDataModule
import eu.kohout.model.manager.ModelModule
import eu.kohout.rest.RestModule
import eu.kohout.tags.LoadDataManagerTag

object Application {

  val modules = Seq(
    new RestModule,
    new ActorSystemModule,
    new ConfigModule,
    new ModelModule,
    new CleanDataModule,
    new LoadDataModule,
    new ResultsAggregatorModule
  )

  def main(args: Array[String]): Unit = {
    val app = new Application(modules)
    app.start
  }
}

class Application(val modules: Seq[AbstractModule]) {
  private val injector: Injector = Guice.createInjector(modules: _*)
  private val log = Logger(getClass)

  val actorSystem = injector.getInstance(classOf[ActorSystem])

  def start(): Unit = {
    injector.getInstance(Key.get(classOf[ActorRef], classOf[LoadDataManagerTag]))
    ()
  }

}
