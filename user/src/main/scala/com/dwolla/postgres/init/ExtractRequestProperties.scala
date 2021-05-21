package com.dwolla.postgres.init

import cats._
import cats.syntax.all._
import com.dwolla.lambda.cloudformation.CloudFormationCustomResourceRequest
import io.circe.Decoder
import io.circe.generic.semiauto._
import io.circe.refined._
import org.typelevel.log4cats.Logger

case class UserConnectionInfo(database: Database,
                              host: Host,
                              port: Port,
                              user: Username,
                              password: Password,
                             )

object UserConnectionInfo {
  implicit val UserConnectionInfoDecoder: Decoder[UserConnectionInfo] = deriveDecoder[UserConnectionInfo]
}

object ExtractRequestProperties {
  def apply[F[_] : MonadThrow : Logger](req: CloudFormationCustomResourceRequest): F[(MasterDatabaseUsername, MasterDatabasePassword, SecretId)] =
    req
      .ResourceProperties
      .toRightNel(InvalidInputException("Missing ResourceProperties"))
      .flatMap { props =>
        (
          FindProperty[MasterDatabaseUsername]("MasterDatabaseUsername"),
          FindProperty[MasterDatabasePassword]("MasterDatabasePassword"),
          FindProperty[SecretId]("UserConnectionSecret"),
          )
          .applyAll(props)
          .parTupled
      }
      .leftMap(InvalidInputsException(_))
      .liftTo[F]
      .flatTap {
        case (user, _, secretId) =>
          Logger[F].info(s"Received request for $user / $secretId")
      }
}
