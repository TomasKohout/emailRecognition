import Dependencies.{akkaCluster, akkaDistributedData}
import sbt._

object Versions {
  val heikoseebergCirce = "1.22.0"

  val emailParser = "1.0.4"

  val akkaHttp = "10.1.4"
  val smileCore = "1.5.2"
  val akkaActor = "2.5.16"
  val scalaLogging = "3.9.0"
  val guice = "4.2.0"
  val typesafeConfig = "1.3.3"
  val logback = "1.2.3"
  val circe = "0.10.0"
  val jclOverSlf4j = "1.7.21"
  val commonsLoggingEmpty = "99-empty"
}

object Dependencies {
  lazy val akkaDistributedData = "com.typesafe.akka" %% "akka-distributed-data" % Versions.akkaActor
  lazy val akkaCluster = "com.typesafe.akka" %% "akka-cluster" % Versions.akkaActor
  lazy val akkaClusterSharding = "com.typesafe.akka" %% "akka-cluster-sharding" % Versions.akkaActor
  lazy val akkaHttp = "com.typesafe.akka" %% "akka-http" % Versions.akkaHttp
  lazy val akkaActor = "com.typesafe.akka" %% "akka-actor" % Versions.akkaActor
  lazy val smileCore = "com.github.haifengl" %% "smile-scala" % Versions.smileCore
  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % Versions.scalaLogging
  lazy val googleGuice = "com.google.inject" % "guice" % Versions.guice
  lazy val typesafeConfig = "com.typesafe" % "config" % Versions.typesafeConfig
  lazy val emailParser = "tech.blueglacier" % "email-mime-parser" % Versions.emailParser
  lazy val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % Versions.akkaActor
  lazy val logback = "ch.qos.logback" % "logback-classic" % Versions.logback
  lazy val circeCore = "io.circe" %% "circe-core" % Versions.circe
  lazy val circeGeneric = "io.circe" %% "circe-generic" % Versions.circe
  lazy val circeParser = "io.circe" %% "circe-parser" % Versions.circe
  lazy val akkaHttpCirce = "de.heikoseeberger" %% "akka-http-circe" % Versions.heikoseebergCirce
  lazy val jclOverSlf4j = "org.slf4j" % "jcl-over-slf4j" % Versions.jclOverSlf4j
  lazy val commonsLoggingEmpty = "commons-logging" % "commons-logging" % Versions.commonsLoggingEmpty

}
