package com.dwolla.postgres.init
package aws

import cats.effect._
import cats.syntax.all._
import com.dwolla.fs2aws.AwsEval
import io.circe.Decoder
import io.circe.parser.parse
import org.typelevel.log4cats.Logger
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest

trait SecretsManagerAlg[F[_]] {
  def getSecret(secretId: SecretId): F[String]
  def getSecretAs[A : Decoder](secretId: SecretId): F[A]
}

object SecretsManagerAlg {
  def resource[F[_] : Concurrent : ContextShift : Logger](blocker: Blocker): Resource[F, SecretsManagerAlg[F]] =
    Resource.fromAutoCloseableBlocking(blocker)(Sync[F].delay(SecretsManagerAsyncClient.builder().build()))
      .map(SecretsManagerAlg[F](_))

  def apply[F[_] : Concurrent : Logger](client: SecretsManagerAsyncClient): SecretsManagerAlg[F] = new SecretsManagerAlg[F] {
    override def getSecret(secretId: SecretId): F[String] =
      Logger[F].info(s"retrieving secret id $secretId") >>
        AwsEval.eval[F](GetSecretValueRequest.builder().secretId(secretId.value).build())(client.getSecretValue)(_.secretString())

    override def getSecretAs[A: Decoder](secretId: SecretId): F[A] =
      for {
        secretString <- getSecret(secretId)
        secretJson <- parse(secretString).liftTo[F]
        a <- secretJson.as[A].liftTo[F]
      } yield a
  }
}
