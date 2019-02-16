import sbt._

object Versions {
  val emailParser = "1.0.4"

  val akkaHttp = "10.1.4"
  val smileCore = "1.5.2"
  val akkaActor = "2.5.16"
  val scalaLogging = "3.9.0"
  val guice = "4.2.0"
  val typesafeConfig = "1.3.3"
}

object Dependencies {
  lazy val akkaHttp = "com.typesafe.akka" %% "akka-http" % Versions.akkaHttp
  lazy val akkaActor = "com.typesafe.akka" %% "akka-actor" % Versions.akkaActor
  lazy val smileCore = "com.github.haifengl" %% "smile-scala" % Versions.smileCore
  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % Versions.scalaLogging
  lazy val googleGuice = "com.google.inject" % "guice" % Versions.guice
  lazy val typesafeConfig = "com.typesafe" % "config" % Versions.typesafeConfig
  lazy val emailParser = "tech.blueglacier" % "email-mime-parser" % Versions.emailParser

}
