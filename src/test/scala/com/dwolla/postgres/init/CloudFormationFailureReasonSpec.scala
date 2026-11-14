package com.dwolla.postgres.init

import cats.data.Kleisli
import cats.effect.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import com.amazonaws.secretsmanager.{SecretIdType, SecretStringType}
import com.comcast.ip4s.*
import com.dwolla.postgres.init.aws.{Secret, SecretString, SecretsManagerAlg}
import com.dwolla.postgres.init.repositories.*
import com.dwolla.postgres.init.repositories.CreateSkunkSession.InSession
import feral.lambda.cloudformation.*
import eu.timepit.refined.refineV
import feral.lambda.{Context, Invocation}
import io.circe.syntax.*
import io.circe.{Decoder, Json}
import munit.CatsEffectSuite
import natchez.Trace.Implicits.noop
import natchez.{Trace, TraceValue}
import org.http4s.client.Client
import org.http4s.syntax.all.*
import org.http4s.{HttpApp, Response, Status}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.testing.TestingLogger
import skunk.data.{Completion, Type}
import skunk.exception.PostgresErrorException

import scala.concurrent.duration.*

@annotation.experimental
class CloudFormationFailureReasonSpec extends CatsEffectSuite {
  private given Logger[IO] = NoOpLogger[IO]

  private val passwordMarker = "made-up-password-marker"
  private val masterPasswordMarker = "made-up-master-password-marker"
  private val database = Database(sqlIdentifier"made_up_db")
  private val user = Username(sqlIdentifier"made_up_user")
  private val secretId = SecretIdType("made-up-secret-id")

  private def secretJson(password: String): Json =
    Json.obj(
      "database" -> database.value.value.asJson,
      "host" -> "made-up-host.example".asJson,
      "port" -> 5432.asJson,
      "user" -> user.value.value.asJson,
      "password" -> password.asJson,
    )

  test("a statement the database rejects is reported by step, user and SQLSTATE, without the statement text") {
    val session = new InMemorySession(command =>
      IO.raiseError(new PostgresErrorException(
        sql = command.sql,
        sqlOrigin = None,
        info = Map('S' -> "ERROR", 'C' -> "42501", 'M' -> "permission denied to create role"),
        history = Nil,
      ))
    )

    responseFor(secretsReturning(secretJson(passwordMarker)), session).map { response =>
      assertEquals(response.hcursor.get[String]("Status"), Right("FAILED"))
      assertEquals(
        response.hcursor.get[String]("Reason"),
        Right("creating or updating user made_up_user failed: SQLSTATE 42501 (InsufficientPrivilege)")
      )
      assertEquals(markersIn(response), List.empty[String])
    }
  }

  test("a user secret that does not decode is reported by secret id, without its contents") {
    val session = new InMemorySession(_ => IO.pure(Completion.CreateRole))

    responseFor(secretsReturning(secretJson(s"$passwordMarker'")), session).map { response =>
      assertEquals(response.hcursor.get[String]("Status"), Right("FAILED"))
      assertEquals(
        response.hcursor.get[String]("Reason"),
        Right("reading secret made-up-secret-id failed: could not decode .password")
      )
      assertEquals(markersIn(response), List.empty[String])
    }
  }

  test("an error the resource raises itself is reported with its own message") {
    val session = new InMemorySession(command =>
      IO.raiseError(new PostgresErrorException(
        sql = command.sql,
        sqlOrigin = None,
        info = Map('S' -> "ERROR", 'C' -> "2BP01", 'M' -> "role cannot be dropped because some objects depend on it"),
        history = Nil,
      ))
    )

    TestControl.executeEmbed(responseFor(secretsReturning(secretJson(passwordMarker)), session, deleteRequest)).map { response =>
      assertEquals(response.hcursor.get[String]("Status"), Right("FAILED"))
      assertEquals(
        response.hcursor.get[String]("Reason"),
        Right("dropping user made_up_user failed: Dependent objects still exist that prevent the removal of made_up_user, but the specified number of retries have been exhausted")
      )
      assertEquals(markersIn(response), List.empty[String])
    }
  }

  test("an error from an AWS call is reported with its message") {
    val session = new InMemorySession(_ => IO.pure(Completion.CreateRole))
    val awsError = new RuntimeException("Made-up service error message (Service: SecretsManager, Status Code: 400, Request ID: made-up-request-id)")

    responseFor(secretsFailingWith(awsError), session).map { response =>
      assertEquals(response.hcursor.get[String]("Status"), Right("FAILED"))
      assertEquals(
        response.hcursor.get[String]("Reason"),
        Right(s"reading secret made-up-secret-id failed: ${awsError.getMessage}")
      )
    }
  }

  test("a failed step's database error is logged and traced with the step, SQLSTATE, and Postgres's message, detail, and hint, without the statement") {
    val step = "creating extension made_up_extension in database made_up_db"
    val extensionError = new PostgresErrorException(
      sql = s"""CREATE EXTENSION IF NOT EXISTS "made_up_extension" CASCADE -- $statementMarker""",
      sqlOrigin = None,
      info = Map(
        'S' -> "ERROR",
        'C' -> "42501",
        'M' -> "permission denied to create extension \"made_up_extension\"",
        'D' -> "Made-up detail about the extension.",
        'H' -> "Made-up hint about the extension.",
      ),
      history = Nil,
      arguments = List(Type.text -> statementMarker.some),
    )
    val requestWithExtension = createRequest.copy(
      ResourceProperties = createRequest.ResourceProperties.copy(extensions = List(madeUpExtension))
    )

    telemetryFor(
      secretsReturning(secretJson(passwordMarker)),
      new InMemorySession(_ => IO.pure(Completion.CreateRole)),
      databaseRepository(createExtensionResult = Kleisli.liftF(IO.raiseError[Unit](extensionError))),
      requestWithExtension,
    ).map { telemetry =>
      assertEquals(
        telemetry.response.hcursor.get[String]("Reason"),
        Right(s"$step failed: SQLSTATE 42501 (InsufficientPrivilege)")
      )
      assertEquals(
        telemetry.warnings,
        Vector(s"""$step failed: SQLSTATE 42501 (InsufficientPrivilege); message: Permission denied to create extension "made_up_extension".; detail: Made-up detail about the extension.; hint: Made-up hint about the extension.""")
      )
      assertEquals(
        telemetry.errorFields,
        List(
          "error.step" -> step,
          "error.sqlstate" -> "42501 (InsufficientPrivilege)",
          "error.postgres.message" -> "Permission denied to create extension \"made_up_extension\".",
          "error.detail" -> "Made-up detail about the extension.",
          "error.hint" -> "Made-up hint about the extension.",
        )
      )
      assertEquals(telemetry.containing(statementMarker), List.empty[String])
    }
  }

  test("a failed step whose statement carries a password verifier is logged and traced by step and SQLSTATE only") {
    val step = "creating or updating user made_up_user"
    val session = new InMemorySession(_ =>
      IO.raiseError(new PostgresErrorException(
        sql = s"CREATE USER made_up_user WITH PASSWORD '$statementMarker'",
        sqlOrigin = None,
        info = Map(
          'S' -> "ERROR",
          'C' -> "42601",
          'M' -> s"syntax error at or near \"$statementMarker\"",
          'D' -> s"Made-up detail quoting $statementMarker.",
          'H' -> s"Made-up hint quoting $statementMarker.",
          'P' -> "42",
        ),
        history = Nil,
        arguments = List(Type.text -> statementMarker.some),
      ))
    )

    telemetryFor(secretsReturning(secretJson(passwordMarker)), session).map { telemetry =>
      assertEquals(
        telemetry.response.hcursor.get[String]("Reason"),
        Right(s"$step failed: SQLSTATE 42601 (SyntaxError)")
      )
      assertEquals(telemetry.warnings, Vector(s"$step failed: SQLSTATE 42601 (SyntaxError)"))
      assertEquals(
        telemetry.errorFields,
        List("error.step" -> step, "error.sqlstate" -> "42601 (SyntaxError)")
      )
      assertEquals(telemetry.containing(statementMarker), List.empty[String])
      assertEquals(markersIn(telemetry.response), List.empty[String])
    }
  }

  test("a step failure inside another step is logged and traced once, under the inner step") {
    val step = "granting privileges in database made_up_db to pgadmin"
    val grantError = new PostgresErrorException(
      sql = "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO pgadmin",
      sqlOrigin = None,
      info = Map('S' -> "ERROR", 'C' -> "42704", 'M' -> "role \"pgadmin\" does not exist"),
      history = Nil,
    )

    telemetryFor(
      secretsReturning(secretJson(passwordMarker)),
      new InMemorySession(_ => IO.pure(Completion.CreateRole)),
      databaseRepository(grantResult = Kleisli.liftF(IO.raiseError[Unit](grantError))),
    ).map { telemetry =>
      assertEquals(
        telemetry.response.hcursor.get[String]("Reason"),
        Right(s"$step failed: SQLSTATE 42704 (UndefinedObject)")
      )
      assertEquals(
        telemetry.warnings,
        Vector(s"""$step failed: SQLSTATE 42704 (UndefinedObject); message: Role "pgadmin" does not exist.""")
      )
      assertEquals(
        telemetry.errorFields,
        List(
          "error.step" -> step,
          "error.sqlstate" -> "42704 (UndefinedObject)",
          "error.postgres.message" -> "Role \"pgadmin\" does not exist.",
        )
      )
    }
  }

  test("a failed role grant for a user is logged and traced with Postgres's message, detail, and hint, under its own step") {
    val step = "adding user made_up_user to the role for database made_up_db"
    val grantRoleError = new PostgresErrorException(
      sql = "GRANT made_up_db_role TO made_up_user",
      sqlOrigin = None,
      info = Map(
        'S' -> "ERROR",
        'C' -> "42704",
        'M' -> "role \"made_up_db_role\" does not exist",
        'D' -> "Made-up detail about the role.",
        'H' -> "Made-up hint about the role.",
      ),
      history = Nil,
    )

    telemetryFor(
      secretsReturning(secretJson(passwordMarker)),
      new InMemorySession(_ => IO.pure(Completion.CreateRole)),
      roleRepository = roleRepository(addUserToRoleResult = Kleisli.liftF(IO.raiseError[Unit](grantRoleError))),
    ).map { telemetry =>
      assertEquals(
        telemetry.response.hcursor.get[String]("Reason"),
        Right(s"$step failed: SQLSTATE 42704 (UndefinedObject)")
      )
      assertEquals(
        telemetry.warnings,
        Vector(s"""$step failed: SQLSTATE 42704 (UndefinedObject); message: Role "made_up_db_role" does not exist.; detail: Made-up detail about the role.; hint: Made-up hint about the role.""")
      )
      assertEquals(
        telemetry.errorFields,
        List(
          "error.step" -> step,
          "error.sqlstate" -> "42704 (UndefinedObject)",
          "error.postgres.message" -> "Role \"made_up_db_role\" does not exist.",
          "error.detail" -> "Made-up detail about the role.",
          "error.hint" -> "Made-up hint about the role.",
        )
      )
    }
  }

  private val statementMarker = "made-up-statement-marker"

  private val madeUpExtension =
    ExtensionName(refineV[ExtensionNamePredicate]("made_up_extension").fold(e => throw new IllegalArgumentException(e), identity))

  private def markersIn(response: Json): List[String] =
    List(passwordMarker, masterPasswordMarker, "PASSWORD", "CREATE USER").filter(response.noSpaces.contains)

  /** What one failed request sent to CloudFormation, wrote to the log, and put on the trace. */
  private case class Telemetry(response: Json,
                               logged: Vector[TestingLogger.LogMessage],
                               spanFields: List[(String, TraceValue)],
                              ) {
    def warnings: Vector[String] = logged.collect { case TestingLogger.WARN(message, _) => message }

    def errorFields: List[(String, String)] =
      spanFields.collect { case (key, value) if key.startsWith("error.") => key -> value.value.toString }

    /** Every place `marker` appears: the response, a log message or its throwable, or a span field. */
    def containing(marker: String): List[String] =
      (response.noSpaces :: logged.toList.flatMap(m => m.message :: m.throwOpt.map(_.toString).toList) ++ spanFields.map(_.toString))
        .filter(_.contains(marker))
  }

  private def telemetryFor(secrets: SecretsManagerAlg[IO],
                           session: InMemorySession,
                           databaseRepository: DatabaseRepository[InSession[IO, *]] = databaseRepository(),
                           request: CloudFormationCustomResourceRequest[DatabaseMetadata] = createRequest,
                           roleRepository: RoleRepository[InSession[IO, *]] = roleRepository(),
                          ): IO[Telemetry] =
    for {
      logger <- TestingLogger.ref[IO]()
      recorded <- Ref[IO].of(List.empty[(String, TraceValue)])
      trace <- Trace.ioTrace(new RecordingSpan(recorded))
      response <- responseFor(secrets, session, request, databaseRepository, roleRepository)(using logger, trace)
      logged <- logger.logged
      spanFields <- recorded.get
    } yield Telemetry(response, logged, spanFields)

  private def responseFor(secrets: SecretsManagerAlg[IO],
                          session: InMemorySession,
                          request: CloudFormationCustomResourceRequest[DatabaseMetadata] = createRequest,
                          databaseRepository: DatabaseRepository[InSession[IO, *]] = databaseRepository(),
                          roleRepository: RoleRepository[InSession[IO, *]] = roleRepository(),
                         )
                         (using Logger[IO], Trace[IO]): IO[Json] =
    for {
      captured <- Deferred[IO, Json]
      handler = {
        given CreateSkunkSession[IO] = InMemorySession.creating(session)
        PostgresqlDatabaseInitHandlerImpl[IO](secrets, databaseRepository, roleRepository, UserRepository[IO])
      }
      _ <- {
        given Invocation[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]] = Invocation.pure(request, context)
        CloudFormationCustomResource(capturingClient(captured), handler)
      }
      response <- captured.get
    } yield response

  private def secretsReturning(secret: Json): SecretsManagerAlg[IO] = new SecretsManagerAlg[IO] {
    override def getSecret(secretId: SecretIdType): IO[Secret] =
      IO.raiseError(new UnsupportedOperationException("not used by these tests"))

    override def getSecretAs[A: Decoder](secretId: SecretIdType): IO[A] =
      SecretsManagerAlg.decodeSecret[A](SecretString(SecretStringType(secret.noSpaces))).liftTo[IO]
  }

  private def secretsFailingWith(error: Throwable): SecretsManagerAlg[IO] = new SecretsManagerAlg[IO] {
    override def getSecret(secretId: SecretIdType): IO[Secret] = IO.raiseError(error)
    override def getSecretAs[A: Decoder](secretId: SecretIdType): IO[A] = IO.raiseError(error)
  }

  private def databaseRepository(grantResult: InSession[IO, Unit] = Kleisli.pure(()),
                                 createExtensionResult: InSession[IO, Unit] = Kleisli.pure(()),
                                ): DatabaseRepository[InSession[IO, *]] = new DatabaseRepository[InSession[IO, *]] {
    override def createDatabase(db: DatabaseMetadata): InSession[IO, Database] = Kleisli.pure(db.name)
    override def removeDatabase(database: Database): InSession[IO, Database] = Kleisli.pure(database)
    override def grantAllPrivilegesOnAllTablesInSchemaPublicToPgadmin: InSession[IO, Unit] = grantResult
    override def createExtension(extension: ExtensionName): InSession[IO, Unit] = createExtensionResult
  }

  private def roleRepository(addUserToRoleResult: InSession[IO, Unit] = Kleisli.pure(())): RoleRepository[InSession[IO, *]] = new RoleRepository[InSession[IO, *]] {
    override def createRole(database: Database): InSession[IO, Unit] = Kleisli.pure(())
    override def removeRole(database: Database): InSession[IO, Unit] = Kleisli.pure(())
    override def addUserToRole(username: Username, database: Database): InSession[IO, Unit] = addUserToRoleResult
    override def removeUserFromRole(username: Username, database: Database): InSession[IO, Unit] = Kleisli.pure(())
  }

  private def capturingClient(captured: Deferred[IO, Json]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { req =>
      req.as[String]
        .flatMap(io.circe.parser.parse(_).liftTo[IO])
        .flatMap(captured.complete)
        .as(Response[IO](Status.Ok))
    })

  private val createRequest = CloudFormationCustomResourceRequest[DatabaseMetadata](
    RequestType = CloudFormationRequestType.CreateRequest,
    ResponseURL = uri"https://made-up.example/response",
    StackId = StackId("made-up-stack"),
    RequestId = RequestId("made-up-request"),
    ResourceType = ResourceType("Custom::PostgresqlDatabase"),
    LogicalResourceId = LogicalResourceId("MadeUpResource"),
    PhysicalResourceId = None,
    ResourceProperties = DatabaseMetadata(
      host = host"made-up-host.example",
      port = port"5432",
      name = database,
      username = MasterDatabaseUsername(sqlIdentifier"made_up_master"),
      password = MasterDatabasePassword(masterPasswordMarker),
      secretIds = List(secretId),
    ),
    OldResourceProperties = None,
  )

  private val deleteRequest = createRequest.copy(
    RequestType = CloudFormationRequestType.DeleteRequest,
    PhysicalResourceId = PhysicalResourceId(database.value.value),
  )

  private val context = Context[IO](
    functionName = "made-up-function",
    functionVersion = "1",
    invokedFunctionArn = "made-up-arn",
    memoryLimitInMB = 1024,
    awsRequestId = "made-up-request-id",
    logGroupName = "made-up-log-group",
    logStreamName = "made-up-log-stream",
    identity = None,
    clientContext = None,
    remainingTime = 1.minute.pure[IO],
  )
}
