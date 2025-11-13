ThisBuild / organization := "com.dwolla"
ThisBuild / description := "CloudFormation custom resource to initialize a PostgreSQL database with a new user"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/postgresql-init-custom-resource"))
ThisBuild / licenses += ("MIT", url("https://opensource.org/licenses/MIT"))
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / developers := List(
  Developer(
    "bpholt",
    "Brian Holt",
    "bholt+postgres-init-custom-resource@dwolla.com",
    url("https://dwolla.com")
  ),
)
ThisBuild / startYear := Option(2021)
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots

lazy val smithy = (project in file("smithy"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.dwolla" %% "natchez-smithy4s" % "0.1.1",
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-cats" % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-json" % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-aws-http4s" % smithy4sVersion.value
    ),
    smithy4sAwsSpecs ++= Seq(AWS.secretsManager)
  )

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty
ThisBuild / githubWorkflowPublish := Seq.empty

lazy val circeV = "0.14.15"

lazy val `postgresql-init-core` = (project in file("."))
  .settings(
    maintainer := developers.value.head.email,
    topLevelDirectory := None,
    libraryDependencies ++= {
      val natchezVersion = "0.3.8"
      val feralVersion = "0.3.1-79-260ee83-SNAPSHOT"

      Seq(
        "org.typelevel" %% "feral-lambda-cloudformation-custom-resource" % feralVersion,
        "org.tpolecat" %% "natchez-xray" % natchezVersion,
        "org.tpolecat" %% "natchez-http4s" % "0.6.1",
        "org.typelevel" %% "cats-tagless-core" % "0.16.3-85-591274f-SNAPSHOT", // see https://github.com/typelevel/cats-tagless/issues/652
        "org.http4s" %% "http4s-ember-client" % "0.23.32",
        "io.circe" %% "circe-parser" % circeV,
        "io.circe" %% "circe-generic" % circeV,
        "io.circe" %% "circe-literal" % circeV,
        "io.circe" %% "circe-refined" % "0.14.9",
        "io.monix" %% "newtypes-core" % "0.3.0",
        "io.monix" %% "newtypes-circe-v0-14" % "0.3.0",
        "org.tpolecat" %% "skunk-core" % "0.6.4",
        "org.typelevel" %% "log4cats-slf4j" % "2.7.1",
        "com.amazonaws" % "aws-lambda-java-log4j2" % "1.6.0",
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.25.2",
        "com.dwolla" %% "natchez-tagless" % "0.2.6-131-d6a1c7c-SNAPSHOT",
        "org.typelevel" %% "mouse" % "1.4.0",
        "com.comcast" %% "ip4s-core" % "3.7.0",
        "org.scalameta" %% "munit" % "1.2.1" % Test,
        "org.scalameta" %% "munit-scalacheck" % "1.2.0" % Test,
        "io.circe" %% "circe-literal" % circeV % Test,
        "com.dwolla" %% "dwolla-otel-natchez" % "0.2.8" % Test,
      )
    },
    buildInfoPackage := "com.dwolla.buildinfo.postgres.init",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
  )
  .dependsOn(smithy)
  .enablePlugins(UniversalPlugin, JavaAppPackaging, BuildInfoPlugin)

lazy val serverlessDeployCommand = settingKey[Seq[String]]("serverless command to deploy the application")
serverlessDeployCommand := "serverless deploy --verbose".split(' ').toSeq

lazy val deploy = inputKey[Int]("deploy to AWS")
deploy := Def.inputTask {
  import scala.sys.process.Process

  val commandParts = serverlessDeployCommand.value ++ Seq("--stage", Stage.parser.parsed.name)
  streams.value.log.log(Level.Info, commandParts.mkString(" "))

  val exitCode = Process(
    commandParts,
    Option((`postgresql-init-core` / baseDirectory).value),
    "DATABASE_ARTIFACT_PATH" -> (`postgresql-init-core` / Universal / packageBin).value.toString,
  ).!

  if (exitCode == 0) exitCode
  else throw new IllegalStateException("Serverless returned a non-zero exit code. Please check the logs for more information.")
}.evaluated
