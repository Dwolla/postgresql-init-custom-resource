package com.dwolla.postgres.init

import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import com.dwolla.postgres.init.aws.{ResourceNotFoundException, SecretsManagerAlg}
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import com.dwolla.postgres.init.repositories._
import feral.cloudformation.{CloudFormationCustomResource, CloudFormationCustomResourceHandler, CloudFormationCustomResourceRequest, HandlerResponse, PhysicalResourceId, tagPhysicalResourceId}
import fs2.io.net.Network
import natchez.Trace
import natchez.Trace.Implicits.noop
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class PostgresqlDatabaseInitHandler extends CloudFormationCustomResourceHandler[Logger[IO], DatabaseMetadata, Unit] {
  override def cfnSetup: Resource[IO, Logger[IO]] = Resource.eval(Slf4jLogger.create[IO])

  override def handler(setup: Logger[IO]): CloudFormationCustomResource[IO, DatabaseMetadata, Unit] = {
    implicit val L: Logger[IO] = setup
    new PostgresqlDatabaseInitHandlerImpl[IO](
      SecretsManagerAlg.resource[IO],
      DatabaseRepository[IO],
      RoleRepository[IO],
      UserRepository[IO],
    )
  }
}

class PostgresqlDatabaseInitHandlerImpl[F[_] : Concurrent : Trace : Network : Console : Logger](secretsManagerAlg: Resource[F, SecretsManagerAlg[F]],
                                                                                                databaseRepository: DatabaseRepository[InSession[F, *]],
                                                                                                roleRepository: RoleRepository[InSession[F, *]],
                                                                                                userRepository: UserRepository[InSession[F, *]],
                                                                                               ) extends CloudFormationCustomResource[F, DatabaseMetadata, Unit] {
  private def databaseAsPhysicalResourceId(db: Database): PhysicalResourceId =
    tagPhysicalResourceId(db.value)

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
    secretsManagerAlg.use(sm => input.secretIds.traverse(sm.getSecretAs[UserConnectionInfo])).flatMap { userPasswords =>
      f(userPasswords).inSession(input.host, input.port, input.username, input.password)
    }

  private def getUsernamesFromSecrets(secretIds: List[SecretId], fallback: Username): F[List[Username]] =
    secretsManagerAlg.use { sm =>
      secretIds.traverse { secretId =>
        sm.getSecretAs[UserConnectionInfo](secretId)
          .map(_.user)
          .recoverWith {
            case ex: ResourceNotFoundException =>
              Logger[F].warn(ex)(s"could not retrieve secret ${secretId.value}, falling back to ${fallback.value}")
                .as(fallback)
          }
      }
    }

  override def createResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata]): F[HandlerResponse[Unit]] =
    handleCreateOrUpdate(event.ResourceProperties)(createOrUpdate(_, event.ResourceProperties)).map(HandlerResponse(_, None))

  override def updateResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata]): F[HandlerResponse[Unit]] =
    handleCreateOrUpdate(event.ResourceProperties)(createOrUpdate(_, event.ResourceProperties)).map(HandlerResponse(_, None))

  override def deleteResource(event: CloudFormationCustomResourceRequest[DatabaseMetadata]): F[HandlerResponse[Unit]] =
    getUsernamesFromSecrets(event.ResourceProperties.secretIds, UserRepository.usernameForDatabase(event.ResourceProperties.name))
      .flatMap { usernames =>
        (for {
          _ <- usernames.traverse(roleRepository.removeUserFromRole(_, event.ResourceProperties.name))
          db <- databaseRepository.removeDatabase(event.ResourceProperties.name)
          _ <- roleRepository.removeRole(event.ResourceProperties.name)
          _ <- usernames.traverse(userRepository.removeUser)
        } yield databaseAsPhysicalResourceId(db)).inSession(event.ResourceProperties.host, event.ResourceProperties.port, event.ResourceProperties.username, event.ResourceProperties.password)
      }.map(HandlerResponse(_, None))

}
