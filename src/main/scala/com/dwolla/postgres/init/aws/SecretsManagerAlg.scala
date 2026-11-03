package com.dwolla.postgres.init
package aws

import cats.*
import cats.effect.{Trace as _, *}
import cats.syntax.all.*
import cats.tagless.aop.*
import cats.tagless.Derive
import com.amazonaws.secretsmanager.*
import com.dwolla.tagless.WeaveKnot
import com.dwolla.tracing.syntax.*
import io.circe.jawn.JawnParser
import io.circe.{Decoder, Errors}
import natchez.{Trace, TraceableValue}
import org.typelevel.log4cats.Logger

trait SecretsManagerAlg[F[_]] {
  def getSecret(secretId: SecretIdType): F[Secret]
  def getSecretAs[A : {Decoder, TraceableValue}](secretId: SecretIdType): F[A]
}

@annotation.experimental
object SecretsManagerAlg {
  given Aspect[SecretsManagerAlg, TraceableValue, TraceableValue] = Derive.aspect

  def apply[F[_] : {Async, Logger, Trace}](client: SecretsManager[F]): SecretsManagerAlg[F] =
    WeaveKnot[SecretsManagerAlg, F](apply(client, _))(_.traceWithInputsAndOutputs)

  private def apply[F[_] : {Async, Logger}](client: SecretsManager[F],
                                            self: Eval[SecretsManagerAlg[F]],
                                           ): SecretsManagerAlg[F] = new SecretsManagerAlg[F] {
    private val parser = new JawnParser

    override def getSecret(secretId: SecretIdType): F[Secret] =
      Logger[F].info(s"retrieving secret id $secretId") >>
        client.getSecretValue(secretId)
          .flatMap {
            case GetSecretValueResponse(_, _, _, None, Some(txt), _, _) =>
              SecretString(txt).pure[F].widen
            case GetSecretValueResponse(_, _, _, Some(blob), None, _, _) =>
              SecretBinary(blob).pure[F].widen
            case GetSecretValueResponse(_, _, _, Some(blob), Some(_), _, _) =>
              SecretBinary(blob).pure[F].widen
            case _ =>
              NoSecretInResponseException(secretId).raiseError[F, Secret]
          }

    override def getSecretAs[A : {Decoder, TraceableValue}](secretId: SecretIdType): F[A] =
      self.value.getSecret(secretId)
        .flatMap {
          case SecretString(SecretStringType(value)) => parser.parse(value).liftTo[F]
          case SecretBinary(SecretBinaryType(value)) => parser.parseByteBuffer(value.asByteBuffer).liftTo[F]
        }
        .flatMap {
          _.asAccumulating[A]
            .toEither
            .leftMap(Errors(_))
            .liftTo[F]
        }
  }
}

sealed trait Secret
case class SecretString(value: SecretStringType) extends Secret
case class SecretBinary(value: SecretBinaryType) extends Secret

object Secret {
  given TraceableValue[Secret] = TraceableValue[String].contramap {
    case SecretString(_) => "redacted string secret"
    case SecretBinary(_) => "redacted binary secret"
  }
}

case class NoSecretInResponseException(resource: SecretIdType) extends RuntimeException(resource.value)
