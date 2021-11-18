package com.dwolla.postgres.init

import cats.data._
import cats.effect._
import cats.effect.kernel.Resource
import cats.effect.std.Console
import cats.syntax.all._
import com.dwolla.postgres.init.aws.{ResourceNotFoundException, SecretsManagerAlg}
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import com.dwolla.postgres.init.repositories._
import feral.lambda
import feral.lambda.IOLambda._
import feral.lambda.cloudformation._
import feral.lambda.tracing._
import feral.lambda.{Context, IOLambda}
import fs2.INothing
import fs2.io.net.Network
import natchez.http4s.NatchezMiddleware
import natchez.{Span, Trace}
import org.http4s.client.{Client, middleware}
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class PostgresqlDatabaseInitHandler extends IOLambda[CloudFormationCustomResourceRequest[DatabaseMetadata], INothing] {
  private def httpClient[F[_] : Async : Trace]: Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .build
      .map(middleware.Logger[F](logHeaders = true, logBody = false))
      .map(NatchezMiddleware.client(_))

  private def handler[F[_] : Async : Console : Trace]: Resource[F, lambda.Lambda[F, CloudFormationCustomResourceRequest[DatabaseMetadata], INothing]] =
      httpClient[F].flatMap {
        CloudFormationCustomResource(_) {
          Resource.eval(Slf4jLogger.create[F])
            .flatMap { implicit logger =>
              SecretsManagerAlg.resource[F]
                .map {
                  new PostgresqlDatabaseInitHandlerImpl(
                    _,
                    DatabaseRepository[F],
                    RoleRepository[F],
                    UserRepository[F],
                  )
                }
            }
        }
      }

  override def run: Resource[IO, lambda.Lambda[IO, CloudFormationCustomResourceRequest[DatabaseMetadata], INothing]] =
    XRayTracedLambda(handler[Kleisli[IO, Span[IO], *]])
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

  override def createResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata], context: Context[F]): F[HandlerResponse[INothing]] =
    handleCreateOrUpdate(event.ResourceProperties)(createOrUpdate(_, event.ResourceProperties)).map(HandlerResponse(_, None))

  override def updateResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata], context: Context[F]): F[HandlerResponse[INothing]] =
    handleCreateOrUpdate(event.ResourceProperties)(createOrUpdate(_, event.ResourceProperties)).map(HandlerResponse(_, None))

  override def deleteResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata], context: Context[F]): F[HandlerResponse[INothing]] =
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
