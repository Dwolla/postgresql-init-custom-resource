package com.dwolla.postgres.init

import cats.*
import cats.effect.std.Console
import cats.effect.{Trace as _, *}
import cats.syntax.all.*
import cats.tagless.aop.Aspect
import cats.tagless.{Derive, Trivial}
import com.amazonaws.secretsmanager.{ResourceNotFoundException, SecretIdType}
import com.dwolla.postgres.init.aws.SecretsManagerAlg
import com.dwolla.postgres.init.repositories.*
import com.dwolla.postgres.init.repositories.CreateSkunkSession.*
import com.dwolla.tracing.syntax.*
import feral.lambda.INothing
import feral.lambda.cloudformation.*
import fs2.io.net.Network
import natchez.*
import org.typelevel.log4cats.Logger

trait PostgresqlDatabaseInitHandlerImpl[F[_]] extends CloudFormationCustomResource[F, DatabaseMetadata, INothing]

@annotation.experimental
object PostgresqlDatabaseInitHandlerImpl {
  given TraceableValue[feral.lambda.cloudformation.PhysicalResourceId] = TraceableValue[String].contramap(_.value)

  given Aspect[PostgresqlDatabaseInitHandlerImpl, TraceableValue, Trivial] = Derive.aspect

  private[PostgresqlDatabaseInitHandlerImpl] def databaseAsPhysicalResourceId[F[_] : ApplicativeThrow](db: Database): F[PhysicalResourceId] =
    PhysicalResourceId(db.value.value).liftTo[F](new RuntimeException("Database name was invalid as Physical Resource ID"))

  def apply[F[_] : {Temporal, Trace, Network, Console, Logger}](secretsManagerAlg: SecretsManagerAlg[F],
                                                                databaseRepository: DatabaseRepository[InSession[F, *]],
                                                                roleRepository: RoleRepository[InSession[F, *]],
                                                                userRepository: UserRepository[InSession[F, *]],
                                                               ): PostgresqlDatabaseInitHandlerImpl[F] = new PostgresqlDatabaseInitHandlerImpl[F] {
    override def createResource(event: DatabaseMetadata): F[HandlerResponse[INothing]] =
      handleCreateOrUpdate(event)(createOrUpdate(_, event)).map(HandlerResponse(_, None))

    override def updateResource(event: DatabaseMetadata, physicalResourceId: PhysicalResourceId): F[HandlerResponse[INothing]] =
      handleCreateOrUpdate(event)(createOrUpdate(_, event)).map(HandlerResponse(_, None))

    override def deleteResource(event: DatabaseMetadata, physicalResourceId: PhysicalResourceId): F[HandlerResponse[INothing]] =
      for {
        usernames <- getUsernamesFromSecrets(event.secretIds, UserRepository.usernameForDatabase(event.name))
        dbId <- removeUsersFromDatabase(usernames, event.name).inSession(event.host, event.port, event.username, event.password)
      } yield HandlerResponse(dbId, None)

    private def databaseScopedCreateOrUpdateOperations(userPasswords: List[UserConnectionInfo], input: DatabaseMetadata): InSession[F, Unit] =
      for {
        _ <- databaseRepository.grantAllPrivilegesOnAllTablesInSchemaPublicToPgadmin
        _ <- roleRepository.createRole(input.name)
        _ <- userPasswords.traverse { userPassword =>
          userRepository.addOrUpdateUser(userPassword) >> roleRepository.addUserToRole(userPassword.user, userPassword.database)
        }
        usersToRemove <- userRepository.findDefunctUsers(userPasswords.map(_.user), RoleRepository.roleNameForDatabase(input.name))
        _ <- usersToRemove.traverse_(userRepository.removeUser)
      } yield ()

    private def createOrUpdate(userPasswords: List[UserConnectionInfo], input: DatabaseMetadata): F[PhysicalResourceId] =
      for {
        db <- databaseAsPhysicalResourceId[F](input.name)
        scope <- databaseRepository.createDatabase(input).inSession(input.host, input.port, input.username, input.password)
        _ <- databaseScopedCreateOrUpdateOperations(userPasswords, input).inSession(input.host, input.port, input.username, input.password, scope)
      } yield db

    private def handleCreateOrUpdate(input: DatabaseMetadata)
                                    (f: List[UserConnectionInfo] => F[PhysicalResourceId]): F[PhysicalResourceId] =
      for {
        userPasswords <- input.secretIds.traverse(secretsManagerAlg.getSecretAs[UserConnectionInfo])
        id <- f(userPasswords)
      } yield id

    private def getUsernamesFromSecrets(secretIds: List[SecretIdType], fallback: Username): F[List[Username]] =
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
  }.traceWithInputs
}
