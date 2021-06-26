package com.dwolla.postgres.init

import cats.data.Kleisli
import cats.effect._
import cats.syntax.all._
import com.dwolla.lambda.cloudformation._
import com.dwolla.postgres.init.aws.SecretsManagerAlg
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import com.dwolla.postgres.init.repositories._
import natchez.Trace.Implicits.noop
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException

class PostgresqlDatabaseInitHandler[F[_] : BracketThrow : CreateSkunkSession : Logger](secretsManagerAlg: Resource[F, SecretsManagerAlg[F]],
                                                                                       databaseRepository: DatabaseRepository[InSession[F, *]],
                                                                                       roleRepository: RoleRepository[InSession[F, *]],
                                                                                       userRepository: UserRepository[InSession[F, *]]
                                                                                      )
  extends CreateUpdateDeleteHandler[F, DatabaseMetadata] {

  private def databaseAsPhysicalResourceId(db: Database): PhysicalResourceId =
    tagPhysicalResourceId(db.value)

  override protected def extractRequestProperties(req: CloudFormationCustomResourceRequest): F[(DatabaseMetadata, DatabaseConnectionInfo)] =
    ExtractRequestProperties[F](req).fproduct(_.migrateTo[DatabaseConnectionInfo])

  override protected def handleCreateOrUpdate(input: DatabaseMetadata): InSession[F, PhysicalResourceId] =
    for {
      userPasswords <- Kleisli.liftF(secretsManagerAlg.use(sm => input.secretIds.traverse(sm.getSecretAs[UserConnectionInfo])))
      db <- databaseRepository.createDatabase(input)
      _ <- roleRepository.createRole(input.name)
      _ <- userPasswords.traverse { userPassword =>
        userRepository.addOrUpdateUser(userPassword) >> roleRepository.addUserToRole(userPassword.user, userPassword.database)
      }
    } yield databaseAsPhysicalResourceId(db)

  override protected def handleDelete(input: DatabaseMetadata): InSession[F, PhysicalResourceId] =
    for {
      usernames <- Kleisli.liftF(getUsernamesFromSecrets(input.secretIds, UserRepository.usernameForDatabase(input.name)))
      _ <- usernames.traverse(roleRepository.removeUserFromRole(_, input.name))
      db <- databaseRepository.removeDatabase(input.name)
      _ <- roleRepository.removeRole(input.name)
      _ <- usernames.traverse(userRepository.removeUser)
    } yield databaseAsPhysicalResourceId(db)

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
}

class Handler extends IOCustomResourceHandler {
  override def handleRequest(req: CloudFormationCustomResourceRequest): IO[HandlerResponse] =
    Slf4jLogger.create[IO].flatMap { implicit l =>
      Blocker[IO].use { blocker =>
        new PostgresqlDatabaseInitHandler[IO](
          SecretsManagerAlg.resource[IO](blocker),
          DatabaseRepository[IO],
          RoleRepository[IO],
          UserRepository[IO],
        ).handleRequest(req)
      }
    }
}
