ThisBuild / organization := "com.dwolla"
ThisBuild / description := "CloudFormation custom resource to initialize a PostgreSQL database with a new user"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/postgresql-init-custom-resource"))
ThisBuild / licenses += ("MIT", url("https://opensource.org/licenses/MIT"))
ThisBuild / scalaVersion := "2.13.7"
ThisBuild / scalacOptions += "-Ymacro-annotations"
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

ThisBuild / githubWorkflowJavaVersions := Seq("adopt@1.8", "adopt@1.11")
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty
ThisBuild / githubWorkflowPublish := Seq.empty

lazy val munitV = "0.7.29"
lazy val circeV = "0.14.1"

lazy val `postgresql-init-core` = (project in file("core"))
  .settings(
    maintainer := developers.value.head.email,
    topLevelDirectory := None,
    libraryDependencies ++= {
      val natchezVersion = "0.1.6"
      val feralVersion = "0.1.0-M1"

      Seq(
        "org.typelevel" %% "feral-lambda-cloudformation-custom-resource" % feralVersion,
        "org.tpolecat" %% "natchez-xray" % natchezVersion,
        "org.tpolecat" %% "natchez-http4s" % "0.2.0",
        "org.typelevel" %% "cats-tagless-macros" % "0.14.0",
        "org.http4s" %% "http4s-ember-client" % "0.23.7",
        "io.circe" %% "circe-parser" % circeV,
        "io.circe" %% "circe-generic" % circeV,
        "io.circe" %% "circe-refined" % circeV,
        "io.estatico" %% "newtype" % "0.4.4",
        "org.tpolecat" %% "skunk-core" % "0.2.2",
        "org.typelevel" %% "log4cats-slf4j" % "2.1.1",
        "com.chuusai" %% "shapeless" % "2.3.7",
        "com.dwolla" %% "fs2-aws-java-sdk2" % "3.0.0-RC1",
        "software.amazon.awssdk" % "secretsmanager" % "2.17.102",
        "org.scalameta" %% "munit" % munitV % Test,
        "org.scalameta" %% "munit-scalacheck" % munitV % Test,
        "io.circe" %% "circe-literal" % circeV % Test,
      )
    },
  )
  .enablePlugins(UniversalPlugin, JavaAppPackaging)

lazy val `postgresql-init-custom-resource-root` = (project in file("."))
  .aggregate(`postgresql-init-core`)

lazy val serverlessDeployCommand = settingKey[String]("serverless command to deploy the application")
 serverlessDeployCommand := "serverless deploy --verbose"

lazy val deploy = taskKey[Int]("deploy to AWS")
deploy := Def.task {
  import scala.sys.process._

  val exitCode = Process(
    serverlessDeployCommand.value,
    Option((`postgresql-init-custom-resource-root` / baseDirectory).value),
    "DATABASE_ARTIFACT_PATH" -> (`postgresql-init-core` / Universal / packageBin).value.toString,
  ).!

  if (exitCode == 0) exitCode
  else throw new IllegalStateException("Serverless returned a non-zero exit code. Please check the logs for more information.")
}.value
