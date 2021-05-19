inThisBuild(List(
  organization := "com.dwolla",
  description := "CloudFormation custom resource to initialize a PostgreSQL database with a new user",
  homepage := Some(url("https://github.com/Dwolla/postgresql-init-custom-resource")),
  licenses += ("MIT", url("https://opensource.org/licenses/MIT")),
  scalaVersion := "2.13.5",
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.3" cross CrossVersion.full),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  scalacOptions += "-Ymacro-annotations",
  developers := List(
    Developer(
      "bpholt",
      "Brian Holt",
      "bholt+postgres-init-custom-resource@dwolla.com",
      url("https://dwolla.com")
    ),
  ),
  startYear := Option(2021),

  githubWorkflowJavaVersions := Seq("adopt@1.8", "adopt@1.11"),
  githubWorkflowTargetTags ++= Seq("v*"),
  githubWorkflowPublishTargetBranches := Seq.empty,
  githubWorkflowPublish := Seq.empty,
))

lazy val fs2AwsV = "2.0.0-M12"

lazy val `postgresql-init-user` = (project in file("user"))
  .settings(
    maintainer := developers.value.head.email,
    topLevelDirectory := None,
    libraryDependencies ++= {
      Seq(
        "com.dwolla" %% "scala-cloudformation-custom-resource" % "4.0.0-M3",
        "com.dwolla" %% "fs2-aws-java-sdk2" % fs2AwsV,
        "software.amazon.awssdk" % "secretsmanager" % "2.16.62",
      )
    },
  )
  .dependsOn(`postgresql-init-core`)
  .enablePlugins(UniversalPlugin, JavaAppPackaging)

lazy val `postgresql-init-database` = (project in file("db"))
  .settings(
    maintainer := developers.value.head.email,
    topLevelDirectory := None,
    libraryDependencies ++= {
      Seq(
        "com.dwolla" %% "scala-cloudformation-custom-resource" % "4.0.0-M3",
      )
    },
  )
  .dependsOn(`postgresql-init-core`)
  .enablePlugins(UniversalPlugin, JavaAppPackaging)

lazy val `postgresql-init-core` = (project in file("core"))
  .settings(
    libraryDependencies ++= {
      val circeV = "0.13.0"
      val munitV = "0.7.26"

      Seq(
        "com.dwolla" %% "scala-cloudformation-custom-resource" % "4.0.0-M3",
        "io.circe" %% "circe-literal" % circeV,
        "io.circe" %% "circe-parser" % circeV,
        "io.circe" %% "circe-generic" % circeV,
        "io.circe" %% "circe-refined" % circeV,
        "io.estatico" %% "newtype" % "0.4.4",
        "org.tpolecat" %% "skunk-core" % "0.0.24",
        "com.dwolla" %% "fs2-aws" % fs2AwsV,
        "org.typelevel" %% "log4cats-slf4j" % "1.3.1",
        "org.scalameta" %% "munit" % munitV % Test,
        "org.scalameta" %% "munit-scalacheck" % munitV % Test,
      )
    },
  )

lazy val `postgresql-init-custom-resource-root` = (project in file("."))
  .aggregate(`postgresql-init-core`, `postgresql-init-user`, `postgresql-init-database`)

 lazy val serverlessDeployCommand = settingKey[String]("serverless command to deploy the application")
 serverlessDeployCommand := "serverless deploy --verbose"

 lazy val deploy = taskKey[Int]("deploy to AWS")
 deploy := Def.task {
   import scala.sys.process._

   val exitCode = Process(
     serverlessDeployCommand.value,
     Option((`postgresql-init-custom-resource-root` / baseDirectory).value),
     "USER_ARTIFACT_PATH" -> (`postgresql-init-user` / Universal / packageBin).value.toString,
     "DATABASE_ARTIFACT_PATH" -> (`postgresql-init-database` / Universal / packageBin).value.toString,
   ).!

   if (exitCode == 0) exitCode
   else throw new IllegalStateException("Serverless returned a non-zero exit code. Please check the logs for more information.")
 }.value
