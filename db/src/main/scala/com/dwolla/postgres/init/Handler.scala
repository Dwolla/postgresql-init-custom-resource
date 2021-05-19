package com.dwolla.postgres.init

import cats.effect._
import cats.syntax.all._
import com.dwolla.lambda.cloudformation._
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import com.dwolla.postgres.init.repositories._
import natchez.Trace.Implicits.noop
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class PostgresqlDatabaseInitHandler[F[_] : BracketThrow : CreateSkunkSession : Logger](databaseRepository: DatabaseRepository[InSession[F, *]],
                                                                                       roleRepository: RoleRepository[InSession[F, *]])
  extends CreateUpdateDeleteHandler[F, DatabaseMetadata] {

  private def databaseAsPhysicalResourceId(db: Database): PhysicalResourceId =
    tagPhysicalResourceId(db.value)

  override protected def extractRequestProperties(req: CloudFormationCustomResourceRequest): F[(DatabaseMetadata, DatabaseConnectionInfo)] =
    ExtractRequestProperties[F](req).fproduct(_.migrateTo[DatabaseConnectionInfo])

  override protected def handleCreateOrUpdate(input: DatabaseMetadata): InSession[F, PhysicalResourceId] =
    databaseRepository.createDatabase(input)
      .flatTap(_ => roleRepository.createRole(input.name))
      .map(databaseAsPhysicalResourceId)

  override protected def handleDelete(input: DatabaseMetadata): InSession[F, PhysicalResourceId] =
    roleRepository.removeRole(input.name) >>
      databaseRepository.removeDatabase(input.name)
        .map(databaseAsPhysicalResourceId)
}

class Handler extends IOCustomResourceHandler {
  override def handleRequest(req: CloudFormationCustomResourceRequest): IO[HandlerResponse] =
    Slf4jLogger.create[IO].flatMap { implicit l =>
      new PostgresqlDatabaseInitHandler[IO](DatabaseRepository[IO], RoleRepository[IO]).handleRequest(req)
    }
}
