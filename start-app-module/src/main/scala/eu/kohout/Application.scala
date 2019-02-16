package eu.kohout

import com.google.inject.{AbstractModule, Guice, Injector}
import eu.kohout.actorsystem.ActorSystemModule
import eu.kohout.cleandata.CleanDataModule
import eu.kohout.config.ConfigModule
import eu.kohout.loaddata.LoadDataModule
import eu.kohout.model.manager.ModelModule
import eu.kohout.rest.RestModule

object Application {

  val modules = Seq(
    new RestModule,
    new ActorSystemModule,
    new ConfigModule,
    new ModelModule,
    new CleanDataModule,
    new LoadDataModule
  )

  def main(args: Array[String]): Unit = {
    val app = new Application(modules)
    app.start
  }
}

class Application(val modules: Seq[AbstractModule]) {
  private val injector: Injector = Guice.createInjector(modules: _*)

  def start(): Unit =
    ()

}
