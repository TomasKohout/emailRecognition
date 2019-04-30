import Dependencies._

lazy val commonSettings = Seq(
  organization := "eu.kohout",
  scalaVersion := "2.12.7",
  resolvers ++= Seq(
    "Sonatype Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
    "Sonatype Releases" at "http://oss.sonatype.org/content/repositories/releases",
    "version99 Empty loggers" at "http://version99.qos.ch"
  ),
  libraryDependencies ++= Seq(
    commonsLoggingEmpty,
    jclOverSlf4j,
    scalaLogging,
    akkaSlf4j,
    logback
  ),
  exportJars := true,
  scalacOptions ++= Seq(
    "-deprecation", // Emit warning and location for usages of deprecated APIs.
    "-encoding",
    "utf-8", // Specify character encoding used by source files.
    "-explaintypes", // Explain type errors in more detail.
    "-feature", // Emit warning and location for usages of features that should be imported explicitly.
    "-language:existentials", // Existential types (besides wildcard types) can be written and inferred
    "-language:experimental.macros", // Allow macro definition (besides implementation and application)
    "-language:higherKinds", // Allow higher-kinded types
    "-language:implicitConversions", // Allow definition of implicit functions called views
    "-language:postfixOps", // Enable postfix operations.
    "-unchecked", // Enable additional warnings where generated code depends on assumptions.
    //"-Xcheckinit", // Wrap field accessors to throw an exception on uninitialized access.
    "-Xfatal-warnings", // Fail the compilation if there are any warnings.
    "-Xfuture", // Turn on future language features.
    //"-Xlint:adapted-args",               // Warn if an argument list is modified to match the receiver.
    "-Xlint:by-name-right-associative", // By-name parameter of right associative operator.
    "-Xlint:constant", // Evaluation of a constant arithmetic expression results in an error.
    "-Xlint:delayedinit-select", // Selecting member of DelayedInit.
    "-Xlint:doc-detached", // A Scaladoc comment appears to be detached from its element.
    "-Xlint:inaccessible", // Warn about inaccessible types in method signatures.
    "-Xlint:infer-any", // Warn when a type argument is inferred to be `Any`.
    "-Xlint:missing-interpolator", // A string literal appears to be missing an interpolator id.
    "-Xlint:nullary-override", // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Xlint:nullary-unit", // Warn when nullary methods return Unit.
    "-Xlint:option-implicit", // Option.apply used implicit view.
    "-Xlint:package-object-classes", // Class or object defined in package object.
    "-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
    "-Xlint:private-shadow", // A private field (or class parameter) shadows a superclass field.
    "-Xlint:stars-align", // Pattern sequence wildcard must align with sequence component.
    "-Xlint:type-parameter-shadow", // A local type parameter shadows a type already in scope.
    "-Xlint:unsound-match", // Pattern match may not be typesafe.
    "-Ypartial-unification", // Enable partial unification in type constructor inference
    "-Ywarn-extra-implicit", // Warn when more than one implicit parameter section is defined.
    "-Ywarn-inaccessible", // Warn about inaccessible types in method signatures.
    "-Ywarn-infer-any", // Warn when a type argument is inferred to be `Any`.
    "-Ywarn-nullary-override", // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Ywarn-nullary-unit", // Warn when nullary methods return Unit.
    "-Ywarn-numeric-widen", // Warn when numerics are widened.
        "-Ywarn-unused:implicits", // Warn if an implicit parameter is unused.
        "-Ywarn-unused:imports", // Warn if an import selector is not referenced.
//        "-Ywarn-unused:locals", // Warn if a local definition is unused.
        "-Ywarn-unused:params", // Warn if a value parameter is unused.
//        "-Ywarn-unused:patvars", // Warn if a variable bound in a pattern is unused.
//        "-Ywarn-unused:privates", // Warn if a private member is unused.
    "-Ywarn-value-discard" // Warn when non-Unit expression results are unused.
  )
)

inThisBuild(commonSettings)

lazy val `rest-api-module` = (project in file("rest-api-module"))
  .settings(
    libraryDependencies ++= Seq(
      akkaHttp,
      circeCore,
      circeGeneric,
      circeParser,
      akkaHttpCirce,
      enumeratumCirce,
      enumeratum
    )
  )
  .dependsOn(`results-aggregator-module`)

lazy val `model-module` = (project in file("model-module"))
  .settings(
    libraryDependencies ++= Seq(
      akkaActor,
      akkaHttp,
      akkaDistributedData,
      akkaCluster,
      akkaClusterSharding,
      smileCore
    )
  )
  .dependsOn(`results-aggregator-module`)

lazy val `clean-data-module` = (project in file("clean-data-module"))
  .settings(
    libraryDependencies ++= Seq(
      akkaActor,
      akkaHttp,
      akkaDistributedData,
      akkaCluster,
      akkaClusterSharding,
      smileCore,
      jsoup
    ),
  )
  .dependsOn(`model-module`)

lazy val `load-data-module` = (project in file("load-data-module"))
  .settings(
    libraryDependencies ++= Seq(
      akkaActor,
      akkaHttp,
      akkaDistributedData,
      akkaCluster,
      akkaClusterSharding
    )
  )
  .dependsOn(`clean-data-module`)

lazy val `email-parser-module` = (project in file("email-parser-module"))
  .settings(
    libraryDependencies ++= Seq(
      emailParser
    )
  )

lazy val `email-recognition` = (project in file("."))
  .settings(
    mainClass in Compile := Some("eu.kohout.Main"),
    bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/application.conf"""",
    bashScriptExtraDefines += """addJava "-Dlogback.configurationFile=${app_home}/../conf/logback.xml""""
  )
  .dependsOn(
    `rest-api-module`,
    `dictionary-resolver-module`
  )
  .enablePlugins(JavaServerAppPackaging)

lazy val `results-aggregator-module` = (project in file("results-aggregator-module"))
  .settings(
    libraryDependencies ++= Seq(
      akkaActor,
      akkaHttp,
      akkaDistributedData,
      akkaCluster,
      akkaClusterSharding,
      plotly
    )
  )
  .dependsOn(`email-parser-module`)

lazy val `dictionary-resolver-module` = (project in file("dictionary-resolver-module"))
  .dependsOn(`load-data-module`)

assemblyMergeStrategy in assembly := {
  case "application.conf" => MergeStrategy.discard
  case "logback.xml"      => MergeStrategy.discard
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
