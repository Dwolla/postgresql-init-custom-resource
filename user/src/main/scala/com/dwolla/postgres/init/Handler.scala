package com.dwolla.postgres.init

import cats.effect._
import cats.syntax.all._
import com.dwolla.lambda.cloudformation._
import com.dwolla.postgres.init.aws.SecretsManagerAlg
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import com.dwolla.postgres.init.repositories.{CreateSkunkSession, RoleRepository, UserRepository}
import natchez.Trace.Implicits.noop
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class PostgresqlUserInitHandler[F[_] : BracketThrow : CreateSkunkSession : Logger](secretsManagerAlg: Resource[F, SecretsManagerAlg[F]],
                                                                                   userRepository: UserRepository[InSession[F, *]],
                                                                                   roleRepository: RoleRepository[InSession[F, *]],
                                                                                  ) extends CreateUpdateDeleteHandler[F, UserConnectionInfo] {
  private def usernameAsPhysicalResourceId(username: Username): PhysicalResourceId =
    tagPhysicalResourceId(username.value)

  override protected def extractRequestProperties(req: CloudFormationCustomResourceRequest): F[(UserConnectionInfo, DatabaseConnectionInfo)] =
    for {
      (dbUser, dbPass, secretId) <- ExtractRequestProperties[F](req)
      userConnectionInfo <- secretsManagerAlg.use(_.getSecretAs[UserConnectionInfo](secretId))
    } yield (userConnectionInfo, DatabaseConnectionInfo(userConnectionInfo.host, userConnectionInfo.port, dbUser, dbPass))

  override protected def handleCreateOrUpdate(input: UserConnectionInfo): InSession[F, PhysicalResourceId] =
    userRepository
      .addOrUpdateUser(input)
      .flatTap(_ => roleRepository.addUserToRole(input.user, input.database))
      .map(usernameAsPhysicalResourceId)

  override protected def handleDelete(input: UserConnectionInfo): InSession[F, PhysicalResourceId] =
    roleRepository.removeUserFromRole(input.user, input.database) >>
      userRepository
        .removeUser(input)
        .map(usernameAsPhysicalResourceId)
}

class Handler extends IOCustomResourceHandler {
  override def handleRequest(req: CloudFormationCustomResourceRequest): IO[HandlerResponse] = {
    Slf4jLogger.create[IO].flatMap { implicit l =>
      Blocker[IO].use { blocker =>
        new PostgresqlUserInitHandler[IO](SecretsManagerAlg.resource[IO](blocker), UserRepository[IO], RoleRepository[IO]).handleRequest(req)
      }
    }
  }
}
