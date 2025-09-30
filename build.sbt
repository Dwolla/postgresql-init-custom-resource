ThisBuild / organization := "com.dwolla"
ThisBuild / description := "CloudFormation custom resource to initialize a PostgreSQL database with a new user"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/postgresql-init-custom-resource"))
ThisBuild / licenses += ("MIT", url("https://opensource.org/licenses/MIT"))
ThisBuild / scalaVersion := "2.13.15"
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
  compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.4" cross CrossVersion.full),
  compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
)

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("8"), JavaSpec.temurin("11"))
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty
ThisBuild / githubWorkflowPublish := Seq.empty

lazy val munitV = "1.2.0"
lazy val circeV = "0.14.15"

lazy val `postgresql-init-core` = (project in file("."))
  .settings(
    maintainer := developers.value.head.email,
    topLevelDirectory := None,
    libraryDependencies ++= {
      val natchezVersion = "0.3.8"
      val feralVersion = "0.3.1"

      Seq(
        "org.typelevel" %% "feral-lambda-cloudformation-custom-resource" % feralVersion,
        "org.tpolecat" %% "natchez-xray" % natchezVersion,
        "org.tpolecat" %% "natchez-http4s" % "0.6.1",
        "org.typelevel" %% "cats-tagless-macros" % "0.16.3",
        "org.http4s" %% "http4s-ember-client" % "0.23.32",
        "io.circe" %% "circe-parser" % circeV,
        "io.circe" %% "circe-generic" % circeV,
        "io.circe" %% "circe-refined" % circeV,
        "io.monix" %% "newtypes-core" % "0.3.0",
        "io.monix" %% "newtypes-circe-v0-14" % "0.3.0",
        "org.tpolecat" %% "skunk-core" % "0.3.2",
        "org.typelevel" %% "log4cats-slf4j" % "2.7.1",
        "com.amazonaws" % "aws-lambda-java-log4j2" % "1.6.0",
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.25.2",
        "com.chuusai" %% "shapeless" % "2.3.13",
        "com.dwolla" %% "fs2-aws-java-sdk2" % "3.0.0-RC2",
        "software.amazon.awssdk" % "secretsmanager" % "2.17.295",
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
