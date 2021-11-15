package com.dwolla.postgres.init

import cats._
import cats.data._
import cats.effect._
import cats.effect.kernel.Resource
import cats.effect.std.{Console, Random}
import cats.syntax.all._
import com.dwolla.postgres.init.aws.{ResourceNotFoundException, SecretsManagerAlg}
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import com.dwolla.postgres.init.repositories._
import feral.lambda
import feral.lambda.{Context, IOLambda, Lambda}
import feral.lambda.cloudformation._
import feral.lambda.tracing.XRayKleisliTracedLambda
import fs2.io.net.Network
import natchez.{Kernel, Span, Trace, TraceValue}
import natchez.xray._
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.URI

class PostgresqlDatabaseInitHandler extends IOLambda[CloudFormationCustomResourceRequest[DatabaseMetadata], Unit] {
  private def tracedHandler: Resource[Kleisli[IO, Span[IO], *], Lambda[Kleisli[IO, Span[IO], *], CloudFormationCustomResourceRequest[DatabaseMetadata], Unit]] =
    EmberClientBuilder
      .default[Kleisli[IO, Span[IO], *]]
      .build
      .flatMap {
        CloudFormationCustomResource(_) {
          Resource.eval(Slf4jLogger.create[Kleisli[IO, Span[IO], *]])
            .flatMap { implicit logger =>
              SecretsManagerAlg.resource[Kleisli[IO, Span[IO], *]]
                .map {
                  new PostgresqlDatabaseInitHandlerImpl[Kleisli[IO, Span[IO], *]](
                    _,
                    DatabaseRepository[Kleisli[IO, Span[IO], *]],
                    RoleRepository[Kleisli[IO, Span[IO], *]],
                    UserRepository[Kleisli[IO, Span[IO], *]],
                  )
                }
            }
        }
      }

  override def run: Resource[IO, lambda.Lambda[IO, CloudFormationCustomResourceRequest[DatabaseMetadata], Unit]] =
    Resource.eval(Random.scalaUtilRandom[IO]).flatMap { implicit random =>
      Resource.eval(XRayEnvironment[IO].daemonAddress)
        .flatMap {
          case Some(addr) => XRay.entryPoint[IO](addr)
          case None => XRay.entryPoint[IO]()
        }
        .flatMap { entrypoint =>
          tracedHandler
            .map { l =>
              val toTrace = new XRayKleisliTracedLambda[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]]
              toTrace(entrypoint, Kleisli.liftK)(l)
            }
            .mapK(Kleisli.applyK(new NoOpSpan[IO]))
        }
    }

}

class NoOpSpan[F[_] : Applicative] extends Span[F] {
  override def put(fields: (String, TraceValue)*): F[Unit] = ().pure[F]
  override def kernel: F[Kernel] = Kernel(Map.empty).pure[F]
  override def span(name: String): Resource[F, Span[F]] = Resource.pure[F, Span[F]](this)
  override def traceId: F[Option[String]] = none[String].pure[F]
  override def spanId: F[Option[String]] = none[String].pure[F]
  override def traceUri: F[Option[URI]] = none[URI].pure[F]
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
