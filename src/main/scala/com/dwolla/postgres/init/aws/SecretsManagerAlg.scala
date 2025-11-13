package com.dwolla.postgres.init
package aws

import cats.*
import cats.effect.{Trace as _, *}
import cats.syntax.all.*
import cats.tagless.aop.*
import com.amazonaws.secretsmanager.*
import com.dwolla.tagless.WeaveKnot
import com.dwolla.tracing.syntax.*
import io.circe.jawn.JawnParser
import io.circe.{Decoder, Errors}
import natchez.{Trace, TraceableValue}
import org.typelevel.log4cats.Logger

trait SecretsManagerAlg[F[_]] {
  def getSecret(secretId: SecretIdType): F[Secret]
  def getSecretAs[A : Decoder](secretId: SecretIdType): F[A]
}

object SecretsManagerAlg {
  implicit val traceableValueAspect: Aspect[SecretsManagerAlg, TraceableValue, TraceableValue] = new Aspect[SecretsManagerAlg, TraceableValue, TraceableValue] {
    override def weave[F[_]](af: SecretsManagerAlg[F]): SecretsManagerAlg[Aspect.Weave[F, TraceableValue, TraceableValue, *]] =
      new SecretsManagerAlg[Aspect.Weave[F, TraceableValue, TraceableValue, *]] {
        override def getSecret(secretId: SecretIdType): Aspect.Weave[F, TraceableValue, TraceableValue, Secret] =
          Aspect.Weave(
            "SecretsManagerAlg",
            List(List(
              Aspect.Advice.byValue("secretId", secretId),
            )),
            Aspect.Advice("getSecret", af.getSecret(secretId))
          )

        override def getSecretAs[A: Decoder](secretId: SecretIdType): Aspect.Weave[F, TraceableValue, TraceableValue, A] =
          Aspect.Weave(
            "SecretsManagerAlg",
            List(
              List(Aspect.Advice.byValue("secretId", secretId)),
              List(Aspect.Advice.byValue("implicit decoder", Decoder[A].toString)),
            ),
            Aspect.Advice("getSecretAs", af.getSecretAs[A](secretId))(using TraceableValue[String].contramap[A](_ => "redacted successfully parsed and decoded secret"))
          )
      }

    override def mapK[F[_], G[_]](af: SecretsManagerAlg[F])(fk: F ~> G): SecretsManagerAlg[G] =
      new SecretsManagerAlg[G] {
        override def getSecret(secretId: SecretIdType): G[Secret] = fk(af.getSecret(secretId))
        override def getSecretAs[A: Decoder](secretId: SecretIdType): G[A] = fk(af.getSecretAs[A](secretId))
      }
  }

  def apply[F[_] : Async : Logger : Trace](client: SecretsManager[F]): SecretsManagerAlg[F] =
    WeaveKnot[SecretsManagerAlg, F](apply(client, _))(_.traceWithInputsAndOutputs)

  private def apply[F[_] : Async : Logger](client: SecretsManager[F],
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

    override def getSecretAs[A: Decoder](secretId: SecretIdType): F[A] =
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
  implicit val traceableValue: TraceableValue[Secret] = TraceableValue[String].contramap {
    case SecretString(_) => "redacted string secret"
    case SecretBinary(_) => "redacted binary secret"
  }
}

case class NoSecretInResponseException(resource: SecretIdType) extends RuntimeException(resource.value)
