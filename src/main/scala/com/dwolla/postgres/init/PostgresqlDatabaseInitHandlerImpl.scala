package com.dwolla.postgres.init

import cats._
import cats.effect.std.Console
import cats.effect.{Trace => _, _}
import cats.syntax.all._
import com.dwolla.postgres.init.PostgresqlDatabaseInitHandlerImpl.databaseAsPhysicalResourceId
import com.dwolla.postgres.init.aws.{ResourceNotFoundException, SecretsManagerAlg}
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import com.dwolla.postgres.init.repositories._
import feral.lambda.INothing
import feral.lambda.cloudformation._
import fs2.io.net.Network
import natchez._
import org.typelevel.log4cats.Logger

class PostgresqlDatabaseInitHandlerImpl[F[_] : Concurrent : Trace : Network : Console : Logger](secretsManagerAlg: SecretsManagerAlg[F],
                                                                                                databaseRepository: DatabaseRepository[InSession[F, *]],
                                                                                                roleRepository: RoleRepository[InSession[F, *]],
                                                                                                userRepository: UserRepository[InSession[F, *]],
                                                                                               ) extends CloudFormationCustomResource[F, DatabaseMetadata, INothing] {
  override def createResource(event: DatabaseMetadata): F[HandlerResponse[INothing]] =
    handleCreateOrUpdate(event)(createOrUpdate(_, event)).map(HandlerResponse(_, None))

  override def updateResource(event: DatabaseMetadata): F[HandlerResponse[INothing]] =
    handleCreateOrUpdate(event)(createOrUpdate(_, event)).map(HandlerResponse(_, None))

  override def deleteResource(event: DatabaseMetadata): F[HandlerResponse[INothing]] =
    for {
      usernames <- getUsernamesFromSecrets(event.secretIds, UserRepository.usernameForDatabase(event.name))
      dbId <- removeUsersFromDatabase(usernames, event.name).inSession(event.host, event.port, event.username, event.password)
    } yield HandlerResponse(dbId, None)

  private def createOrUpdate(userPasswords: List[UserConnectionInfo], input: DatabaseMetadata): InSession[F, PhysicalResourceId] =
    for {
      db <- databaseAsPhysicalResourceId[InSession[F, *]](input.name)
      _ <- databaseRepository.createDatabase(input)
      _ <- roleRepository.createRole(input.name)
      _ <- userPasswords.traverse { userPassword =>
        userRepository.addOrUpdateUser(userPassword) >> roleRepository.addUserToRole(userPassword.user, userPassword.database)
      }
    } yield db

  private def handleCreateOrUpdate(input: DatabaseMetadata)
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

  private def removeUsersFromDatabase(usernames: List[Username], databaseName: Database): InSession[F, PhysicalResourceId] =
    for {
      db <- databaseAsPhysicalResourceId[InSession[F, *]](databaseName)
      _ <- usernames.traverse(roleRepository.removeUserFromRole(_, databaseName))
      _ <- databaseRepository.removeDatabase(databaseName)
      _ <- roleRepository.removeRole(databaseName)
      _ <- usernames.traverse(userRepository.removeUser)
    } yield db
}

object PostgresqlDatabaseInitHandlerImpl {
  def apply[F[_] : Temporal : Trace : Network : Console : Logger](secretsManager: SecretsManagerAlg[F]): PostgresqlDatabaseInitHandlerImpl[F] =
    new PostgresqlDatabaseInitHandlerImpl(
      secretsManager,
      DatabaseRepository[F],
      RoleRepository[F],
      UserRepository[F],
    )

  private[PostgresqlDatabaseInitHandlerImpl] def databaseAsPhysicalResourceId[F[_] : ApplicativeThrow](db: Database): F[PhysicalResourceId] =
    PhysicalResourceId(db.value).liftTo[F](new RuntimeException("Database name was invalid as Physical Resource ID"))
}
