package com.dwolla.postgres.init

import cats._
import cats.data._
import cats.effect.std.{Console, Random}
import cats.effect.{Trace => _, _}
import cats.syntax.all._
import com.dwolla.postgres.init.PostgresqlDatabaseInitHandler.nothingEncoder
import com.dwolla.postgres.init.aws.{ResourceNotFoundException, SecretsManagerAlg}
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import com.dwolla.postgres.init.repositories._
import feral.lambda.cloudformation._
import feral.lambda.natchez.TracedLambda
import feral.lambda.{IOLambda, LambdaEnv}
import fs2.INothing
import fs2.io.net.Network
import io.circe.Encoder
import natchez._
import natchez.http4s.NatchezMiddleware
import natchez.xray.XRay
import org.http4s.client.{Client, middleware}
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.annotation.nowarn

object PostgresqlDatabaseInitHandler {
  //noinspection NotImplementedCode
  @nowarn("msg=dead code following this construct")
  implicit val nothingEncoder: Encoder[INothing] = _ => ???
}

class PostgresqlDatabaseInitHandler extends IOLambda[CloudFormationCustomResourceRequest[DatabaseMetadata], Unit] {
  private implicit def kleisliLogger[F[_] : Logger, A]: Logger[Kleisli[F, A, *]] = Logger[F].mapK(Kleisli.liftK)

  private implicit def kleisliLambdaEnv[F[_] : Functor, A, B](implicit env: LambdaEnv[F, A]): LambdaEnv[Kleisli[F, B, *], A] =
    env.mapK(Kleisli.liftK)

  private def httpClient[F[_] : Async]: Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .build
      .map(middleware.Logger[F](logHeaders = true, logBody = false))

  private def resources[F[_] : Async : Console]: Resource[F, LambdaEnv[F, CloudFormationCustomResourceRequest[DatabaseMetadata]] => F[Option[Unit]]] =
    Resource.eval(Slf4jLogger.create[F]).flatMap { implicit logger =>
      for {
        client <- httpClient[F]
        entryPoint <- Resource.eval(Random.scalaUtilRandom[F]).flatMap { implicit r => XRay.entryPoint[F]() }
        secretsManager <- SecretsManagerAlg.resource[F, Kleisli[F, Span[F], *]]
      } yield Kleisli { implicit env: LambdaEnv[F, CloudFormationCustomResourceRequest[DatabaseMetadata]] =>
        TracedLambda[F, CloudFormationCustomResourceRequest[DatabaseMetadata], Unit](entryPoint) { span =>
          val tracedClient = NatchezMiddleware.client(client.translate(Kleisli.liftK[F, Span[F]])(Kleisli.applyK(span)))
          CloudFormationCustomResource(tracedClient, PostgresqlDatabaseInitHandlerImpl(secretsManager)).run(span)
        }
      }.run
    }

  override def handler: Resource[IO, LambdaEnv[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]] => IO[Option[Unit]]] =
    resources[IO]
}

object PostgresqlDatabaseInitHandlerImpl {
  def apply[F[_] : Temporal : Trace : Network : Console : Logger](secretsManager: SecretsManagerAlg[F]): PostgresqlDatabaseInitHandlerImpl[F] =
    new PostgresqlDatabaseInitHandlerImpl(
      secretsManager,
      DatabaseRepository[F],
      RoleRepository[F],
      UserRepository[F],
    )
}

class PostgresqlDatabaseInitHandlerImpl[F[_] : Concurrent : Trace : Network : Console : Logger](secretsManagerAlg: SecretsManagerAlg[F],
                                                                                                databaseRepository: DatabaseRepository[InSession[F, *]],
                                                                                                roleRepository: RoleRepository[InSession[F, *]],
                                                                                                userRepository: UserRepository[InSession[F, *]],
                                                                                               ) extends CloudFormationCustomResource[F, DatabaseMetadata, INothing] {
  private def databaseAsPhysicalResourceId(db: Database): PhysicalResourceId =
    PhysicalResourceId(db.value)

  private def createOrUpdate(userPasswords: List[UserConnectionInfo], input: DatabaseMetadata): InSession[F, PhysicalResourceId] =
    for {
      db <- databaseRepository.createDatabase(input)
      _ <- roleRepository.createRole(input.name)
      _ <- userPasswords.traverse { userPassword =>
        userRepository.addOrUpdateUser(userPassword) >> roleRepository.addUserToRole(userPassword.user, userPassword.database)
      }
    } yield databaseAsPhysicalResourceId(db)

  def handleCreateOrUpdate(input: DatabaseMetadata)
                          (f: List[UserConnectionInfo] => InSession[F, PhysicalResourceId]): F[PhysicalResourceId] =
    for {
      userPasswords <- input.secretIds.traverse(secretsManagerAlg.getSecretAs[UserConnectionInfo])
      id <- f(userPasswords).inSession(input.host, input.port, input.username, input.password)
    } yield id

  private def getUsernamesFromSecrets(secretIds: List[SecretId], fallback: Username): F[List[Username]] =
    secretIds.traverse { secretId =>
      secretsManagerAlg.getSecretAs[UserConnectionInfo](secretId)
        .map(_.user)
        .recoverWith {
          case ex: ResourceNotFoundException =>
            Logger[F].warn(ex)(s"could not retrieve secret ${secretId.value}, falling back to ${fallback.value}")
              .as(fallback)
        }
    }

  override def createResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata]): F[HandlerResponse[INothing]] =
    handleCreateOrUpdate(event.ResourceProperties)(createOrUpdate(_, event.ResourceProperties)).map(HandlerResponse(_, None))

  override def updateResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata]): F[HandlerResponse[INothing]] =
    handleCreateOrUpdate(event.ResourceProperties)(createOrUpdate(_, event.ResourceProperties)).map(HandlerResponse(_, None))

  override def deleteResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata]): F[HandlerResponse[INothing]] =
    for {
      usernames <- getUsernamesFromSecrets(event.ResourceProperties.secretIds, UserRepository.usernameForDatabase(event.ResourceProperties.name))
      dbId <- removeUsersFromDatabase(usernames, event.ResourceProperties.name).inSession(event.ResourceProperties.host, event.ResourceProperties.port, event.ResourceProperties.username, event.ResourceProperties.password)
    } yield HandlerResponse(dbId, None)

  private def removeUsersFromDatabase(usernames: List[Username], databaseName: Database): InSession[F, PhysicalResourceId] =
    for {
      _ <- usernames.traverse(roleRepository.removeUserFromRole(_, databaseName))
      db <- databaseRepository.removeDatabase(databaseName)
      _ <- roleRepository.removeRole(databaseName)
      _ <- usernames.traverse(userRepository.removeUser)
    } yield databaseAsPhysicalResourceId(db)
}
