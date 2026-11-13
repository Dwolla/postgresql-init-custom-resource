package com.dwolla.postgres.init

import cats.*
import cats.data.Kleisli
import cats.effect.{Trace as _, *}
import cats.syntax.all.*
import cats.tagless.aop.Aspect
import cats.tagless.{Derive, Trivial}
import com.amazonaws.secretsmanager.{ResourceNotFoundException, SecretIdType}
import com.dwolla.postgres.init.StepFailed.*
import com.dwolla.postgres.init.aws.SecretsManagerAlg
import com.dwolla.postgres.init.repositories.*
import com.dwolla.postgres.init.repositories.CreateSkunkSession.*
import com.dwolla.tracing.syntax.*
import feral.lambda.INothing
import feral.lambda.cloudformation.*
import natchez.*
import org.typelevel.log4cats.Logger

trait PostgresqlDatabaseInitHandlerImpl[F[_]] extends CloudFormationCustomResource[F, DatabaseMetadata, INothing]

@annotation.experimental
object PostgresqlDatabaseInitHandlerImpl {
  given TraceableValue[feral.lambda.cloudformation.PhysicalResourceId] = TraceableValue[String].contramap(_.value)

  given Aspect[PostgresqlDatabaseInitHandlerImpl, TraceableValue, Trivial] = Derive.aspect

  private[PostgresqlDatabaseInitHandlerImpl] def databaseAsPhysicalResourceId[F[_] : ApplicativeThrow](db: Database): F[PhysicalResourceId] =
    PhysicalResourceId(db.value.value).liftTo[F](new RuntimeException("Database name was invalid as Physical Resource ID"))

  def apply[F[_] : {Temporal, Trace, Logger}](secretsManagerAlg: SecretsManagerAlg[F],
                                               databaseRepository: DatabaseRepository[InSession[F, *]],
                                               roleRepository: RoleRepository[InSession[F, *]],
                                               userRepository: UserRepository[InSession[F, *]],
                                              )
                                              (using CreateSkunkSession[F]): PostgresqlDatabaseInitHandlerImpl[F] = new PostgresqlDatabaseInitHandlerImpl[F] {
    private given [A]: Logger[Kleisli[F, A, *]] = Logger[F].mapK(Kleisli.liftK)

    override def createResource(event: DatabaseMetadata): F[HandlerResponse[INothing]] =
      handleCreateOrUpdate(event)(createOrUpdate(_, event)).map(HandlerResponse(_, None))

    override def updateResource(event: DatabaseMetadata, physicalResourceId: PhysicalResourceId): F[HandlerResponse[INothing]] =
      handleCreateOrUpdate(event)(createOrUpdate(_, event)).map(HandlerResponse(_, None))

    override def deleteResource(event: DatabaseMetadata, physicalResourceId: PhysicalResourceId): F[HandlerResponse[INothing]] =
      for {
        usernames <- getUsernamesFromSecrets(event.secretIds, UserRepository.usernameForDatabase(event.name))
        dbId <- removeUsersFromDatabase(usernames, event.name).inSession(event.host, event.port, event.username, event.password)
                  .failsAsStep(s"removing database ${event.name}")
      } yield HandlerResponse(dbId, None)

    private def databaseScopedCreateOrUpdateOperations(userPasswords: List[UserConnectionInfo], input: DatabaseMetadata): InSession[F, Unit] =
      for {
        _ <- input.extensions.traverse { extension =>
          databaseRepository.createExtension(extension).failsAsStep(s"creating extension $extension in database ${input.name}")
        }
        _ <- databaseRepository.grantAllPrivilegesOnAllTablesInSchemaPublicToPgadmin.failsAsStep(s"granting privileges in database ${input.name} to pgadmin")
        _ <- roleRepository.createRole(input.name).failsAsStep(s"creating the role for database ${input.name}")
        _ <- userPasswords.traverse { userPassword =>
          userRepository.addOrUpdateUser(userPassword).failsAsCredentialStep(s"creating or updating user ${userPassword.user}") >>
            roleRepository.addUserToRole(userPassword.user, userPassword.database)
              .failsAsStep(s"adding user ${userPassword.user} to the role for database ${userPassword.database}")
        }
      } yield ()

    private def createOrUpdate(userPasswords: List[UserConnectionInfo], input: DatabaseMetadata): F[PhysicalResourceId] =
      for {
        db <- databaseAsPhysicalResourceId[F](input.name)
        scope <- databaseRepository.createDatabase(input).inSession(input.host, input.port, input.username, input.password)
                   .failsAsStep(s"creating database ${input.name}")
        _ <- databaseScopedCreateOrUpdateOperations(userPasswords, input).inSession(input.host, input.port, input.username, input.password, scope)
               .failsAsStep(s"configuring database ${input.name}")
      } yield db

    private def handleCreateOrUpdate(input: DatabaseMetadata)
                                    (f: List[UserConnectionInfo] => F[PhysicalResourceId]): F[PhysicalResourceId] =
      for {
        userPasswords <- input.secretIds.traverse { secretId =>
          secretsManagerAlg.getSecretAs[UserConnectionInfo](secretId).failsAsStep(s"reading secret ${secretId.value}")
        }
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
          .failsAsStep(s"reading secret ${secretId.value}")
      }

    private def removeUsersFromDatabase(usernames: List[Username], databaseName: Database): InSession[F, PhysicalResourceId] =
      for {
        db <- databaseAsPhysicalResourceId[InSession[F, *]](databaseName)
        _ <- usernames.traverse { username =>
          roleRepository.removeUserFromRole(username, databaseName).failsAsStep(s"removing user $username from the role for database $databaseName")
        }
        _ <- databaseRepository.removeDatabase(databaseName).failsAsStep(s"dropping database $databaseName")
        _ <- roleRepository.removeRole(databaseName).failsAsStep(s"dropping the role for database $databaseName")
        _ <- usernames.traverse(username => userRepository.removeUser(username).failsAsStep(s"dropping user $username"))
      } yield db
  }.traceWithInputs
}
