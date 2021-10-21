package com.dwolla.postgres.init

import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import com.dwolla.postgres.init.aws.{ResourceNotFoundException, SecretsManagerAlg}
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import com.dwolla.postgres.init.repositories._
import feral.lambda.Context
import feral.lambda.cloudformation._
import fs2.io.net.Network
import natchez.Trace
import natchez.Trace.Implicits.noop
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class PostgresqlDatabaseInitHandler extends IOCloudFormationCustomResourceHandler[DatabaseMetadata, Unit] {
  override def handler(client: Client[IO]): Resource[IO, CloudFormationCustomResource[IO, DatabaseMetadata, Unit]] =
    Resource.eval(Slf4jLogger.create[IO]).flatMap { implicit logger =>
      SecretsManagerAlg.resource[IO]
        .map {
          new PostgresqlDatabaseInitHandlerImpl[IO](
            _,
            DatabaseRepository[IO],
            RoleRepository[IO],
            UserRepository[IO],
          )
        }
    }
}

class PostgresqlDatabaseInitHandlerImpl[F[_] : Concurrent : Trace : Network : Console : Logger](secretsManagerAlg: SecretsManagerAlg[F],
                                                                                                databaseRepository: DatabaseRepository[InSession[F, *]],
                                                                                                roleRepository: RoleRepository[InSession[F, *]],
                                                                                                userRepository: UserRepository[InSession[F, *]],
                                                                                               ) extends CloudFormationCustomResource[F, DatabaseMetadata, Unit] {
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

  override def createResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata], context: Context[F]): F[HandlerResponse[Unit]] =
    handleCreateOrUpdate(event.ResourceProperties)(createOrUpdate(_, event.ResourceProperties)).map(HandlerResponse(_, None))

  override def updateResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata], context: Context[F]): F[HandlerResponse[Unit]] =
    handleCreateOrUpdate(event.ResourceProperties)(createOrUpdate(_, event.ResourceProperties)).map(HandlerResponse(_, None))

  override def deleteResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata], context: Context[F]): F[HandlerResponse[Unit]] =
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
