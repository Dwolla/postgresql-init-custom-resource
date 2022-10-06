ThisBuild / organization := "com.dwolla"
ThisBuild / description := "CloudFormation custom resource to initialize a PostgreSQL database with a new user"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/postgresql-init-custom-resource"))
ThisBuild / licenses += ("MIT", url("https://opensource.org/licenses/MIT"))
ThisBuild / scalaVersion := "2.13.8"
ThisBuild / developers := List(
  Developer(
    "bpholt",
    "Brian Holt",
    "bholt+postgres-init-custom-resource@dwolla.com",
    url("https://dwolla.com")
  ),
)
ThisBuild / startYear := Option(2021)
ThisBuild / libraryDependencies ++= Seq(
  compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
  compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
)

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("8"), JavaSpec.temurin("11"))
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty
ThisBuild / githubWorkflowPublish := Seq.empty

lazy val munitV = "0.7.29"
lazy val circeV = "0.14.3"

lazy val `postgresql-init-core` = (project in file("."))
  .settings(
    maintainer := developers.value.head.email,
    topLevelDirectory := None,
    libraryDependencies ++= {
      val natchezVersion = "0.1.6"
      val feralVersion = "0.1.0-M13"

      Seq(
        "org.typelevel" %% "feral-lambda-cloudformation-custom-resource" % feralVersion,
        "org.tpolecat" %% "natchez-xray" % natchezVersion,
        "org.tpolecat" %% "natchez-http4s" % "0.3.2",
        "org.typelevel" %% "cats-tagless-macros" % "0.14.0",
        "org.http4s" %% "http4s-ember-client" % "0.23.13",
        "io.circe" %% "circe-parser" % circeV,
        "io.circe" %% "circe-generic" % circeV,
        "io.circe" %% "circe-refined" % circeV,
        "io.monix" %% "newtypes-core" % "0.2.3",
        "io.monix" %% "newtypes-circe-v0-14" % "0.2.3",
        "org.tpolecat" %% "skunk-core" % "0.3.1",
        "org.typelevel" %% "log4cats-slf4j" % "2.3.2",
        "com.amazonaws" % "aws-lambda-java-log4j2" % "1.5.1",
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.18.0",
        "com.chuusai" %% "shapeless" % "2.3.9",
        "com.dwolla" %% "fs2-aws-java-sdk2" % "3.0.0-RC1",
        "software.amazon.awssdk" % "secretsmanager" % "2.17.229",
        "org.scalameta" %% "munit" % munitV % Test,
        "org.scalameta" %% "munit-scalacheck" % munitV % Test,
        "io.circe" %% "circe-literal" % circeV % Test,
      )
    },
  )
  .enablePlugins(UniversalPlugin, JavaAppPackaging)

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
