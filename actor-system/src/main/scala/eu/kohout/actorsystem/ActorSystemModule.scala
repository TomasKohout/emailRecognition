package eu.kohout.actorsystem

import akka.actor.ActorSystem
import com.google.inject.{AbstractModule, Provides, Singleton}
import com.typesafe.config.Config

class ActorSystemModule extends AbstractModule {

  @Provides
  @Singleton
  def actorSystem(config: Config): ActorSystem = {
    val name = if (config.hasPath("application.actorSystemName")) {
      config.getString("application.actorSystemName")
    } else {
      "application"
    }

    ActorSystem(name, config)
  }
}
