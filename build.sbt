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

lazy val `postgresql-init-database` = (project in file("db"))
  .settings(
    maintainer := developers.value.head.email,
    topLevelDirectory := None,
    libraryDependencies ++= {
      Seq(
        "org.scalameta" %% "munit" % munitV % Test,
        "io.circe" %% "circe-literal" % circeV % Test,
      )
    },
  )
  .dependsOn(`postgresql-init-core`)
  .enablePlugins(UniversalPlugin, JavaAppPackaging)

lazy val `postgresql-init-core` = (project in file("core"))
  .settings(
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "feral-lambda-cloudformation-custom-resource" % "0.1-18e1226",
        "org.tpolecat" %% "natchez-xray" % "0.1.5+51-eac456af+20211118-1616-SNAPSHOT",
        "org.tpolecat" %% "natchez-http4s" % "0.2.0",
        "org.typelevel" %% "cats-tagless-macros" % "0.14.0",
        "io.circe" %% "circe-parser" % circeV,
        "io.circe" %% "circe-generic" % circeV,
        "io.circe" %% "circe-refined" % circeV,
        "io.estatico" %% "newtype" % "0.4.4",
        "org.tpolecat" %% "skunk-core" % "0.2.2",
        "org.typelevel" %% "log4cats-slf4j" % "2.1.1",
        "com.chuusai" %% "shapeless" % "2.3.7",
        "org.scalameta" %% "munit" % munitV % Test,
        "org.scalameta" %% "munit-scalacheck" % munitV % Test,
      )
    },
  )

lazy val `postgresql-init-custom-resource-root` = (project in file("."))
  .aggregate(`postgresql-init-core`, `postgresql-init-database`)

 lazy val serverlessDeployCommand = settingKey[String]("serverless command to deploy the application")
 serverlessDeployCommand := "serverless deploy --verbose"

 lazy val deploy = taskKey[Int]("deploy to AWS")
 deploy := Def.task {
   import scala.sys.process._

   val exitCode = Process(
     serverlessDeployCommand.value,
     Option((`postgresql-init-custom-resource-root` / baseDirectory).value),
     "DATABASE_ARTIFACT_PATH" -> (`postgresql-init-database` / Universal / packageBin).value.toString,
   ).!

   if (exitCode == 0) exitCode
   else throw new IllegalStateException("Serverless returned a non-zero exit code. Please check the logs for more information.")
 }.value
