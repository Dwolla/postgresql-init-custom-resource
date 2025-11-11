package com.dwolla.postgres.init

import cats.effect.std.{Console, Env, Random}
import cats.effect.syntax.all.*
import cats.effect.{Trace as _, *}
import cats.mtl.Local
import cats.syntax.all.*
import com.amazonaws.secretsmanager.SecretsManager
import com.dwolla.postgres.init.aws.SecretsManagerAlg
import feral.lambda.cloudformation.*
import feral.lambda.{AwsTags, INothing, IOLambda, Invocation, KernelSource}
import fs2.compression.Compression
import fs2.io.file.Files
import fs2.io.net.Network
import mouse.all.*
import natchez.*
import natchez.http4s.NatchezMiddleware
import natchez.mtl.*
import natchez.xray.{XRay, XRayEnvironment}
import org.http4s.client.{Client, middleware}
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import smithy4s.aws.kernel.AwsRegion
import smithy4s.aws.{AwsClient, AwsEnvironment}

class PostgresqlDatabaseInitHandler
  extends IOLambda[CloudFormationCustomResourceRequest[DatabaseMetadata], INothing] {

  private def resources[F[_] : Async : Compression : Console : Env : Files : Network](implicit L: Local[F, Span[F]]): Resource[F, Invocation[F, CloudFormationCustomResourceRequest[DatabaseMetadata]] => F[Option[INothing]]] =
    for {
      case implicit0(logger: Logger[F]) <- Resource.eval(Slf4jLogger.create[F])
      case implicit0(random: Random[F]) <- Resource.eval(Random.scalaUtilRandom[F])
      client <- httpClient[F]
      entryPoint <- XRayEnvironment[F].daemonAddress.toResource.flatMap {
        case Some(addr) => XRay.entryPoint(addr)
        case None => XRay.entryPoint[F]()
      }
      region <- Env[F].get("AWS_REGION").liftEitherT(new RuntimeException("missing AWS_REGION environment variable")).map(AwsRegion(_)).rethrowT.toResource
      awsEnv <- AwsEnvironment.default(client, region)
      secretsManager <- AwsClient(SecretsManager, awsEnv).map(SecretsManagerAlg[F](_))
    } yield { implicit env: Invocation[F, CloudFormationCustomResourceRequest[DatabaseMetadata]] =>
      TracedHandler(entryPoint) { _ =>
        CloudFormationCustomResource(client, PostgresqlDatabaseInitHandlerImpl(secretsManager))
      }
    }

  override def handler: Resource[IO, Invocation[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]] => IO[Option[INothing]]] =
    IO.local(Span.noop[IO]).toResource
      .flatMap(implicit l => resources[IO])

  /**
   * The X-Ray kernel comes from environment variables, so we don't need to extract anything from the incoming event.
   * The kernel will be sourced from the environment/system properties if useEnvironmentFallback is true when
   * initializing the X-Ray entrypoint.
   */
  private implicit def kernelSource[Event]: KernelSource[Event] = KernelSource.emptyKernelSource

  private def httpClient[F[_] : Async : Network : Trace]: Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .build
      .map(middleware.Logger[F](logHeaders = true, logBody = true))
      .map(NatchezMiddleware.client(_))

}

// TODO replace with https://github.com/typelevel/feral/pull/591 once it's merged
object TracedHandler {
  def apply[F[_] : MonadCancelThrow, Event, Result](entryPoint: EntryPoint[F])
                                                   (handler: Trace[F] => F[Option[Result]])
                                                   (implicit inv: Invocation[F, Event],
                                                    KS: KernelSource[Event],
                                                    L: Local[F, Span[F]]): F[Option[Result]] =
    for {
      event <- Invocation[F, Event].event
      context <- Invocation[F, Event].context
      kernel = KernelSource[Event].extract(event)
      result <- entryPoint.continueOrElseRoot(context.functionName, kernel).use {
        Local[F, Span[F]].scope {
          Trace[F].put(
            AwsTags.arn(context.invokedFunctionArn),
            AwsTags.requestId(context.awsRequestId)
          ) >> handler(Trace[F])
        }
      }
    } yield result
}
