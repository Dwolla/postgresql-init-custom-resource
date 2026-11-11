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
import feral.lambda.{Context, Invocation}
import io.circe.syntax.*
import io.circe.{Decoder, Json}
import munit.CatsEffectSuite
import natchez.Trace.Implicits.noop
import org.http4s.client.Client
import org.http4s.syntax.all.*
import org.http4s.{HttpApp, Response, Status}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import skunk.data.Completion
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

  private def markersIn(response: Json): List[String] =
    List(passwordMarker, masterPasswordMarker, "PASSWORD", "CREATE USER").filter(response.noSpaces.contains)

  private def responseFor(secrets: SecretsManagerAlg[IO],
                          session: InMemorySession,
                          request: CloudFormationCustomResourceRequest[DatabaseMetadata] = createRequest,
                         ): IO[Json] =
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

  private val databaseRepository: DatabaseRepository[InSession[IO, *]] = new DatabaseRepository[InSession[IO, *]] {
    override def createDatabase(db: DatabaseMetadata): InSession[IO, Database] = Kleisli.pure(db.name)
    override def removeDatabase(database: Database): InSession[IO, Database] = Kleisli.pure(database)
    override def grantAllPrivilegesOnAllTablesInSchemaPublicToPgadmin: InSession[IO, Unit] = Kleisli.pure(())
    override def createExtension(extension: ExtensionName): InSession[IO, Unit] = Kleisli.pure(())
  }

  private val roleRepository: RoleRepository[InSession[IO, *]] = new RoleRepository[InSession[IO, *]] {
    override def createRole(database: Database): InSession[IO, Unit] = Kleisli.pure(())
    override def removeRole(database: Database): InSession[IO, Unit] = Kleisli.pure(())
    override def addUserToRole(username: Username, database: Database): InSession[IO, Unit] = Kleisli.pure(())
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
