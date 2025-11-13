package com.dwolla.postgres.init

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.dwolla.buildinfo.postgres.init.BuildInfo
import com.dwolla.tracing.{DwollaEnvironment, OpenTelemetryAtDwolla}
import eu.timepit.refined.auto.*
import feral.lambda.cloudformation.*
import feral.lambda.{Context, Invocation}
import org.http4s.syntax.all.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import com.amazonaws.secretsmanager.SecretIdType

import scala.concurrent.duration.*

/**
 * Runs the PostgresqlDatabaseInitHandler locally using a hard-coded input message.
 *
 * You'll need a Postgres database running at the coordinates specified in the input message,
 * and a Secrets Manager secret containing the [[UserConnectionInfo]]. Make sure to set
 * up AWS credentials that have access to retrieve the secret value.
 *
 * View the result at the response URL. There is a webhook.site URL in the example which
 * should continue to work. OTel traces will be emitted and sent to the collector configured
 * via environment variables.
 */
@annotation.experimental
object LocalApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

    OpenTelemetryAtDwolla[IO](BuildInfo.name, BuildInfo.version, DwollaEnvironment.Local)
      .flatMap(new PostgresqlDatabaseInitHandler().handler(_))
      .use { handler =>
        val event = CloudFormationCustomResourceRequest[DatabaseMetadata](
          RequestType = CloudFormationRequestType.CreateRequest,
          ResponseURL = uri"https://webhook.site/0df31272-1810-4b2e-aea6-e4838da33846", // view at https://webhook.site/#!/view/0df31272-1810-4b2e-aea6-e4838da33846/dff27799-9148-4c4c-8b2d-8c60bb2850f0/1
          StackId = StackId("my-stack"),
          RequestId = RequestId("my-request"),
          ResourceType = ResourceType("Custom::PostgresqlDatabaseInitHandler"), // TODO confirm name
          LogicalResourceId = LogicalResourceId("my-resource"),
          PhysicalResourceId = None,
          ResourceProperties = DatabaseMetadata(
            host = host"localhost",
            port = port"5432",
            name = Database(SqlIdentifier.unsafeFrom("transactionactivitymonitor")),
            username = MasterDatabaseUsername(SqlIdentifier.unsafeFrom("root")),
            password = MasterDatabasePassword(SqlIdentifier.unsafeFrom("root")),
            secretIds = List("my-UserConnectionInfo-secret").map(SecretIdType(_)),
          ),
          OldResourceProperties = None,
        )

        val context = Context(functionName = BuildInfo.name,
          functionVersion = BuildInfo.version,
          invokedFunctionArn = "arn",
          memoryLimitInMB = 1024,
          awsRequestId = "request-id",
          logGroupName = "log-group-name",
          logStreamName = "log-stream-name",
          identity = None,
          clientContext = None,
          remainingTime = 60.minutes.pure[IO])

        handler.apply(Invocation.pure(event, context))
      }
      .as(ExitCode.Success)
  }
}
